import asyncio
import os
from contextlib import asynccontextmanager

import asyncpg
import httpx
from fastapi import FastAPI, HTTPException
from pgvector.asyncpg import register_vector
from pydantic import BaseModel, Field

DATABASE_URL = os.environ["DATABASE_URL"]
VOYAGE_URL = os.environ.get("VOYAGE_URL", "https://ai.mongodb.com/v1")
VOYAGE_API_KEY = os.environ["VOYAGE_API_KEY"]
VOYAGE_MODEL = os.environ.get("VOYAGE_MODEL", "voyage-4-large")
RERANK_MODEL = os.environ.get("RERANK_MODEL", "rerank-3")
DIMENSIONS = 1024
BATCH = 128
RETRY_DELAYS = (1, 2, 4)
RERANK_CANDIDATES = 20

SCHEMA = f"""
CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE IF NOT EXISTS passages (
    id text PRIMARY KEY,
    movement_id text NOT NULL,
    first_bar int NOT NULL,
    last_bar int NOT NULL,
    text text NOT NULL,
    embedding vector({DIMENSIONS}) NOT NULL
);
ALTER TABLE passages ADD COLUMN IF NOT EXISTS model text;
CREATE INDEX IF NOT EXISTS passages_embedding ON passages USING hnsw (embedding vector_cosine_ops);
"""


class Passage(BaseModel):
    id: str
    movementId: str
    firstBar: int
    lastBar: int
    text: str


class PassageBatch(BaseModel):
    passages: list[Passage]


class SearchRequest(BaseModel):
    text: str | None = None
    passageId: str | None = None
    k: int = Field(5, ge=1, le=50)
    rerank: bool = False


class Span(BaseModel):
    movementId: str
    firstBar: int
    lastBar: int


class CoveringRequest(BaseModel):
    spans: list[Span] = Field(max_length=50)


class Hit(BaseModel):
    passageId: str
    movementId: str
    firstBar: int
    lastBar: int
    text: str
    score: float


async def init_connection(connection):
    await register_vector(connection)
    await connection.execute("SET hnsw.iterative_scan = relaxed_order")


@asynccontextmanager
async def lifespan(app):
    connection = await asyncpg.connect(DATABASE_URL)
    await connection.execute(SCHEMA)
    await connection.close()
    app.state.db = await asyncpg.create_pool(DATABASE_URL, init=init_connection, min_size=1, max_size=5)
    app.state.voyage = httpx.AsyncClient(
        base_url=VOYAGE_URL, headers={"Authorization": f"Bearer {VOYAGE_API_KEY}"}, timeout=60
    )
    yield
    await app.state.voyage.aclose()
    await app.state.db.close()


app = FastAPI(lifespan=lifespan)


async def voyage(path, body):
    for delay in RETRY_DELAYS:
        response = await app.state.voyage.post(path, json=body)
        if response.status_code != 429:
            break
        await asyncio.sleep(delay)
    else:
        response = await app.state.voyage.post(path, json=body)
    response.raise_for_status()
    return response.json()


async def embed(texts, input_type):
    vectors = []
    for start in range(0, len(texts), BATCH):
        body = await voyage(
            "/embeddings",
            {"input": texts[start : start + BATCH], "model": VOYAGE_MODEL, "input_type": input_type},
        )
        data = sorted(body["data"], key=lambda item: item["index"])
        vectors += [item["embedding"] for item in data]
    return vectors


@app.get("/healthz")
async def health():
    await app.state.db.fetchval("SELECT 1")
    return {"status": "ok"}


@app.post("/passages", status_code=204)
async def upsert(batch: PassageBatch):
    passages = batch.passages
    vectors = {
        row["id"]: row["embedding"]
        for row in await app.state.db.fetch(
            """
            SELECT id, embedding FROM passages JOIN unnest($1::text[], $2::text[]) AS new (id, text) USING (id)
            WHERE passages.text = new.text AND model = $3
            """,
            [p.id for p in passages],
            [p.text for p in passages],
            VOYAGE_MODEL,
        )
    }
    changed = [p for p in passages if p.id not in vectors]
    vectors |= zip([p.id for p in changed], await embed([p.text for p in changed], "document"))
    async with app.state.db.acquire() as connection, connection.transaction():
        await connection.executemany(
            """
            INSERT INTO passages (id, movement_id, first_bar, last_bar, text, embedding, model)
            VALUES ($1, $2, $3, $4, $5, $6, $7)
            ON CONFLICT (id) DO UPDATE SET movement_id = EXCLUDED.movement_id, first_bar = EXCLUDED.first_bar,
                last_bar = EXCLUDED.last_bar, text = EXCLUDED.text, embedding = EXCLUDED.embedding,
                model = EXCLUDED.model
            """,
            [(p.id, p.movementId, p.firstBar, p.lastBar, p.text, vectors[p.id], VOYAGE_MODEL) for p in passages],
        )
        await connection.execute(
            "DELETE FROM passages WHERE movement_id = ANY($1::text[]) AND NOT id = ANY($2::text[])",
            sorted({p.movementId for p in passages}),
            [p.id for p in passages],
        )


@app.post("/search")
async def search(request: SearchRequest):
    if (request.text is None) == (request.passageId is None):
        raise HTTPException(422, "give exactly one of text or passageId")
    source = None
    if request.passageId is not None:
        source = await app.state.db.fetchrow(
            "SELECT movement_id, first_bar, last_bar, text, embedding FROM passages WHERE id = $1", request.passageId
        )
        if source is None:
            raise HTTPException(404, f"unknown passage {request.passageId}")
        vector = source["embedding"]
    else:
        vector = (await embed([request.text], "query"))[0]
    rows = await app.state.db.fetch(
        """
        SELECT id, movement_id, first_bar, last_bar, text, 1 - (embedding <=> $1) AS score
        FROM passages
        WHERE NOT (movement_id = $2 AND first_bar <= $4 AND last_bar >= $3)
        ORDER BY embedding <=> $1
        LIMIT $5
        """,
        vector,
        source["movement_id"] if source else "",
        source["first_bar"] if source else 0,
        source["last_bar"] if source else 0,
        RERANK_CANDIDATES if request.rerank else request.k,
    )
    hits = [
        Hit(passageId=r["id"], movementId=r["movement_id"], firstBar=r["first_bar"], lastBar=r["last_bar"],
            text=r["text"], score=r["score"])
        for r in rows
    ]
    if request.rerank and hits:
        body = await voyage(
            "/rerank",
            {
                "query": request.text if request.text is not None else source["text"],
                "documents": [hit.text for hit in hits],
                "model": RERANK_MODEL,
                "top_k": request.k,
            },
        )
        hits = [hits[item["index"]].model_copy(update={"score": item["relevance_score"]}) for item in body["data"]]
    return {"results": hits}


@app.post("/covering")
async def covering(request: CoveringRequest):
    hits = {}
    for span in request.spans:
        row = await app.state.db.fetchrow(
            """
            SELECT id, movement_id, first_bar, last_bar, text
            FROM passages
            WHERE movement_id = $1 AND first_bar <= $3 AND last_bar >= $2
            ORDER BY (first_bar <= $2 AND last_bar >= $3) DESC, last_bar - first_bar
            LIMIT 1
            """,
            span.movementId,
            span.firstBar,
            span.lastBar,
        )
        if row is not None:
            hits[row["id"]] = Hit(passageId=row["id"], movementId=row["movement_id"], firstBar=row["first_bar"],
                                  lastBar=row["last_bar"], text=row["text"], score=1.0)
    return {"results": list(hits.values())}


@app.delete("/movements/{movement_id}/passages", status_code=204)
async def delete_movement(movement_id: str):
    await app.state.db.execute("DELETE FROM passages WHERE movement_id = $1", movement_id)
