import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

import boto3
import pytest
import yaml

HERE = Path(__file__).resolve().parent
CORPUS = Path(os.environ.get("CORPUS_DIR", HERE.parent / "corpus"))
API = os.environ.get("API_URL", "http://localhost:8080")
ENDPOINT = os.environ.get("AWS_ENDPOINT", "http://localhost:4566")
AWS = {"region_name": "us-east-1"} | (
    {"endpoint_url": ENDPOINT, "aws_access_key_id": "test", "aws_secret_access_key": "test"} if ENDPOINT else {}
)
BUCKET = os.environ.get("CADENCE_BUCKET", "cadence-e2e")
DLQ = os.environ.get("CADENCE_DLQ", "ingest-e2e-dlq")
DLQ_TIMEOUT = int(os.environ.get("DLQ_TIMEOUT", "180"))
CORPUS_DIRS = {"abc": "abc", "bps": "beethoven_piano_sonatas", "mps": "mozart_piano_sonatas"}
GOLDEN = yaml.safe_load((HERE / "golden.yaml").read_text())
NATURAL_LANGUAGE = yaml.safe_load((HERE / "nl.yaml").read_text())
RUN_NL = os.environ.get("RUN_NL") == "1"
SKIP_INGEST = os.environ.get("SKIP_INGEST") == "1"


def wait_for(condition, timeout, what):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if condition():
            return
        time.sleep(2)
    pytest.fail(f"timed out after {timeout}s waiting for {what}")


def post(path, body):
    request = urllib.request.Request(
        f"{API}{path}", data=json.dumps(body).encode(), headers={"content-type": "application/json"}
    )
    try:
        with urllib.request.urlopen(request) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read())


def get(path, params):
    try:
        with urllib.request.urlopen(f"{API}{path}?{urllib.parse.urlencode(params)}") as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read())
    except OSError:
        return None, None


def search(query, corpus):
    hits, cursor = [], None
    while True:
        params = {"q": query, "limit": 200}
        if corpus:
            params["corpus"] = corpus
        if cursor:
            params["cursor"] = cursor
        status, body = get("/search", params)
        if status != 200:
            return status, body, hits
        hits += body["results"]
        cursor = body["nextCursor"]
        if not cursor:
            return status, body, hits


@pytest.fixture(scope="session")
def s3():
    return boto3.client("s3", **AWS)


@pytest.fixture(scope="session")
def ingested(s3):
    wait_for(lambda: get("/search", {"q": "I"})[0] == 200, 120, "search-api")
    if SKIP_INGEST:
        return
    files = [
        (corpus, path)
        for corpus, directory in CORPUS_DIRS.items()
        for path in sorted((CORPUS / directory / "harmonies").glob("*.harmonies.tsv"))
    ]
    assert len(files) == 188, f"expected 188 movement files under {CORPUS}, found {len(files)}"
    started = datetime.now(timezone.utc) - timedelta(seconds=5)
    for corpus, path in files:
        s3.upload_file(str(path), BUCKET, f"raw/{corpus}/{path.name}")

    def passages_written():
        pages = s3.get_paginator("list_objects_v2").paginate(Bucket=BUCKET, Prefix="passages/")
        fresh = [o for page in pages for o in page.get("Contents", []) if o["LastModified"] >= started]
        return len(fresh) == len(files)

    wait_for(passages_written, 900, "all movements to be ingested")


def has_hit(hits, movement, bars):
    return any(h["movementId"] == movement and [h["firstBar"], h["lastBar"]] == bars for h in hits)


@pytest.mark.parametrize("case", GOLDEN, ids=[case["name"] for case in GOLDEN])
def test_golden(ingested, case):
    status, body, hits = search(case["query"], case.get("corpus"))
    assert status == case["status"], body
    for expected in case.get("includes", []):
        assert has_hit(hits, expected["movement"], expected["bars"]), expected
    for unexpected in case.get("excludes", []):
        assert not has_hit(hits, unexpected["movement"], unexpected["bars"]), unexpected
    if "count" in case:
        assert len(hits) == case["count"]


def similar(passage_id, **params):
    return get(f"/passages/{urllib.parse.quote(passage_id, safe='')}/similar", params)


@pytest.mark.parametrize("rerank", [False, True])
def test_similar_passages_skip_overlapping_windows(ingested, s3, rerank):
    lines = s3.get_object(Bucket=BUCKET, Key="passages/bps/01-1.jsonl")["Body"].read().decode().splitlines()
    source = json.loads(lines[0])
    status, body = similar(source["id"], k=5, rerank=str(rerank).lower())
    assert status == 200, body
    hits = body["results"]
    assert len(hits) == 5
    scores = [hit["score"] for hit in hits]
    assert scores == sorted(scores, reverse=True)
    for hit in hits:
        overlaps = hit["firstBar"] <= source["lastBar"] and hit["lastBar"] >= source["firstBar"]
        assert not (hit["movementId"] == source["movementId"] and overlaps), hit


def test_similar_unknown_passage(ingested):
    status, _ = similar("bps:does-not-exist#0")
    assert status == 404


def test_reindex_rewrites_the_movement(ingested, s3):
    key = "passages/bps/01-1.jsonl"
    before = s3.head_object(Bucket=BUCKET, Key=key)["LastModified"]
    status, body = post("/reindex/bps:01-1", {})
    assert status == 202, body
    wait_for(lambda: s3.head_object(Bucket=BUCKET, Key=key)["LastModified"] > before, 120, "bps:01-1 to be reindexed")


def test_reindex_unknown_movement(ingested):
    status, _ = post("/reindex/bps:does-not-exist", {})
    assert status == 404


@pytest.mark.skipif(not RUN_NL, reason="calls the Claude API; set RUN_NL=1")
@pytest.mark.parametrize("case", NATURAL_LANGUAGE, ids=[case["text"] for case in NATURAL_LANGUAGE])
def test_natural_language(ingested, case):
    status, body = post("/nl-search", {"text": case["text"]})
    assert status == case.get("status", 200), body
    if "includes" in case:
        interpretation = body["interpretation"]
        _, _, hits = search(interpretation["query"], interpretation["corpus"] or None)
        expected = case["includes"]
        starts = {(hit["movementId"], hit["firstBar"]) for hit in hits}
        assert (expected["movement"], expected["bars"][0]) in starts, interpretation


@pytest.mark.skipif(SKIP_INGEST, reason="needs a fresh ingest")
def test_malformed_file_lands_in_dead_letter_queue(ingested, s3):
    key = "raw/bps/99-9.harmonies.tsv"
    s3.put_object(Bucket=BUCKET, Key=key, Body=b"label\tchord\nV\tV\n")
    sqs = boto3.client("sqs", **AWS)
    dlq = sqs.get_queue_url(QueueName=DLQ)["QueueUrl"]

    def in_dlq():
        messages = sqs.receive_message(QueueUrl=dlq, MaxNumberOfMessages=10, WaitTimeSeconds=1).get("Messages", [])
        mine = [m for m in messages if key in m["Body"]]
        for message in mine:
            sqs.delete_message(QueueUrl=dlq, ReceiptHandle=message["ReceiptHandle"])
        return bool(mine)

    try:
        wait_for(in_dlq, DLQ_TIMEOUT, f"{key} to reach the dead-letter queue")
    finally:
        s3.delete_object(Bucket=BUCKET, Key=key)


@pytest.mark.skipif(not RUN_NL, reason="calls the Claude API; set RUN_NL=1")
def test_ask_cites_retrieved_passages(ingested):
    status, body = post("/ask", {"question": "Where does Beethoven use a German sixth to approach a perfect authentic cadence in his piano sonatas?"})
    assert status == 200, body
    assert body["citations"], body
    passages = {passage["passageId"]: passage for passage in body["passages"]}
    for citation in body["citations"]:
        passage = passages.get(citation["passageId"])
        assert passage, citation
        assert passage["firstBar"] <= citation["firstBar"] <= citation["lastBar"] <= passage["lastBar"], (citation, passage)
