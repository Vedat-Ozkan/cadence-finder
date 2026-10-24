import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
API = os.environ.get("API_URL", "http://localhost:8080")
EMBEDDER = os.environ.get("EMBEDDER_URL", "http://localhost:8000")
MODEL = os.environ.get("MODEL", "voyage-4-lite")
RUN_NL = os.environ.get("RUN_NL") == "1"
K = 5
SPEC = yaml.safe_load((HERE / "recall.yaml").read_text())
RESULTS = HERE / "results" / "recall.json"


def call(url, body=None):
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(url, data=data, headers={"content-type": "application/json"})
    try:
        with urllib.request.urlopen(request) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read())


def search(query, corpus):
    hits, cursor = [], None
    while True:
        params = {"q": query, "limit": 200} | ({"corpus": corpus} if corpus else {}) | ({"cursor": cursor} if cursor else {})
        status, body = call(f"{API}/search?{urllib.parse.urlencode(params)}")
        if status != 200:
            sys.exit(f"/search {query!r} corpus={corpus}: {status} {body}")
        hits += body["results"]
        cursor = body["nextCursor"]
        if not cursor:
            return hits


def covers(hit, truth):
    return hit["movementId"] == truth["movementId"] and hit["firstBar"] <= truth["firstBar"] and hit["lastBar"] >= truth["lastBar"]


def relevant(hit, truths):
    return any(covers(hit, truth) for truth in truths)


def score(hits, truths):
    found = [relevant(hit, truths) for hit in hits[:K]]
    return {
        "hit": any(found),
        "precision": sum(found) / K,
        "results": [f"{hit['movementId']} {hit['firstBar']}-{hit['lastBar']}" + (" *" if ok else "") for hit, ok in zip(hits, found)],
    }


def text_search(case, rerank):
    status, body = call(f"{EMBEDDER}/search", {"text": case["text"], "k": K, "rerank": rerank})
    if status != 200:
        sys.exit(f"embedder /search {case['name']!r}: {status} {body}")
    return body["results"]


def similar(case, rerank):
    passage = urllib.parse.quote(case["passage"], safe="")
    status, body = call(f"{API}/passages/{passage}/similar?k={K}&rerank={str(rerank).lower()}")
    if status != 200:
        sys.exit(f"/similar {case['passage']}: {status} {body}")
    return body["results"]


def natural_language(case, truths):
    status, body = call(f"{API}/nl-search", {"text": case["text"]})
    if status != 200:
        return {"hit": False, "recall": 0.0, "precision": 0.0, "interpretation": body}
    interpretation = body["interpretation"]
    hits = search(interpretation["query"], interpretation["corpus"] or None)
    found = [truth for truth in truths if any(covers(hit, truth) for hit in hits)]
    correct = [hit for hit in hits if relevant(hit, truths)]
    return {
        "hit": bool(found),
        "recall": len(found) / len(truths),
        "precision": len(correct) / len(hits) if hits else 0.0,
        "interpretation": interpretation,
    }


def evaluate(case):
    truths = search(case["truth"]["query"], case["truth"].get("corpus"))
    if not truths:
        sys.exit(f"ground truth for {case['name']!r} has no hits")
    row = {"name": case["name"], "truth": case["truth"], "truthHits": len(truths), "runs": {}}
    if "text" in case:
        row["text"] = case["text"]
        for rerank in (False, True):
            row["runs"][f"text{'+rerank' if rerank else ''}"] = score(text_search(case, rerank), truths)
        if RUN_NL:
            row["runs"]["nl"] = natural_language(case, truths)
    else:
        row["passage"] = case["passage"]
        for rerank in (False, True):
            row["runs"][f"similar{'+rerank' if rerank else ''}"] = score(similar(case, rerank), truths)
    return row


def summarise(rows):
    runs = {}
    for row in rows:
        for run, result in row["runs"].items():
            runs.setdefault(run, []).append(result)
    return {
        run: {"cases": len(results), "hitRate": sum(r["hit"] for r in results) / len(results),
              "precision": sum(r["precision"] for r in results) / len(results)}
        | ({"recall": sum(r["recall"] for r in results) / len(results)} if run == "nl" else {})
        for run, results in runs.items()
    }


def main():
    rows = [evaluate(case) for case in SPEC["cases"]]
    summary = summarise(rows)
    print(f"model {MODEL}, k={K}, API {API}, embedder {EMBEDDER}")
    print(f"{'case':58} {'truth':>5}  " + "  ".join(f"{run:>15}" for run in summary))
    for row in rows:
        cells = [f"{'hit' if row['runs'][run]['hit'] else '-':>4} p={row['runs'][run]['precision']:.2f}" if run in row["runs"] else "" for run in summary]
        print(f"{row['name'][:58]:58} {row['truthHits']:>5}  " + "  ".join(f"{cell:>15}" for cell in cells))
    print()
    for run, result in summary.items():
        extra = f"  recall={result['recall']:.2f}" if "recall" in result else ""
        print(f"{run:15} n={result['cases']:<3} hit-rate={result['hitRate']:.2f}  precision={result['precision']:.2f}{extra}")
    RESULTS.parent.mkdir(exist_ok=True)
    RESULTS.write_text(json.dumps({"model": MODEL, "k": K, "api": API, "embedder": EMBEDDER, "labels": SPEC["labels"], "metric": SPEC["metric"],
                                   "summary": summary, "cases": rows}, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
