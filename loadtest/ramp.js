import http from "k6/http";
import exec from "k6/execution";
import { check } from "k6";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const RATES = (__ENV.RATES || "5,10,20,40,80").split(",").map(Number);
const STAGE_SECONDS = Number(__ENV.STAGE_SECONDS || 60);
const SUMMARY = __ENV.SUMMARY || "loadtest/results/ramp.json";
const NL = __ENV.NL === "1";

const QUERIES = [
  { q: "I V I IV6", corpus: "abc" },
  { q: "Ger ... @PAC", corpus: "bps" },
  { q: "Ger ... I@PAC", corpus: "bps" },
  { q: "It6 V", corpus: "mps" },
  { q: "Fr6 V", corpus: "abc" },
  { q: "V65/V V", corpus: "bps" },
  { q: "ii6 V@HC", corpus: "mps" },
  { q: "V7 vi@DC", corpus: "bps" },
  { q: "V V7 I@PAC", corpus: "mps" },
  { q: "ii% V i@PAC", corpus: "bps" },
];

const SENTENCES = [
  "German augmented sixth before a perfect authentic cadence in Beethoven piano sonatas",
  "Italian sixth resolving to the dominant at a half cadence in Mozart's piano sonatas",
  "dominant seventh to vi as a deceptive cadence in Beethoven's sonatas",
];

const stageName = (rate) => `${rate}rps`;

const thresholds = {};
for (const rate of RATES) {
  const stage = stageName(rate);
  thresholds[`http_req_duration{stage:${stage}}`] = [
    { threshold: "p(99)<1000", abortOnFail: true, delayAbortEval: "10s" },
  ];
  thresholds[`http_reqs{stage:${stage}}`] = ["count>=0"];
  thresholds[`http_req_failed{stage:${stage}}`] = ["rate>=0"];
}

const scenarios = {
  search: {
    executor: "ramping-arrival-rate",
    exec: "search",
    startRate: RATES[0],
    timeUnit: "1s",
    preAllocatedVUs: Math.max(...RATES),
    maxVUs: Math.max(...RATES) * 4,
    stages: RATES.flatMap((rate) => [
      { target: rate, duration: "0s" },
      { target: rate, duration: `${STAGE_SECONDS}s` },
    ]),
  },
};

if (NL) {
  scenarios.nl = {
    executor: "constant-arrival-rate",
    exec: "nl",
    rate: 1,
    timeUnit: "5s",
    duration: "1m",
    preAllocatedVUs: 2,
    maxVUs: 4,
  };
  thresholds["http_req_duration{scenario:nl}"] = ["max>=0"];
  thresholds["http_reqs{scenario:nl}"] = ["count>=0"];
  thresholds["http_req_failed{scenario:nl}"] = ["rate>=0"];
}

export const options = {
  scenarios,
  thresholds,
  summaryTrendStats: ["min", "med", "p(50)", "p(95)", "p(99)", "max"],
};

export function search() {
  const index = Math.min(Math.floor(exec.scenario.progress * RATES.length), RATES.length - 1);
  const query = QUERIES[exec.scenario.iterationInTest % QUERIES.length];
  const response = http.get(`${BASE_URL}/search?q=${encodeURIComponent(query.q)}&corpus=${query.corpus}&limit=50`, {
    tags: { name: "search", stage: stageName(RATES[index]) },
  });
  check(response, { "search 200": (r) => r.status === 200 });
}

export function nl() {
  const text = SENTENCES[exec.scenario.iterationInTest % SENTENCES.length];
  const response = http.post(`${BASE_URL}/nl-search`, JSON.stringify({ text }), {
    headers: { "Content-Type": "application/json" },
    tags: { name: "nl-search" },
  });
  check(response, { "nl-search 200": (r) => r.status === 200 });
}

function row(label, duration, requests, failed, seconds) {
  if (!duration || !requests) {
    return `${label.padEnd(8)} no requests`;
  }
  const d = duration.values;
  const ms = (v) => `${v.toFixed(0)}ms`.padStart(8);
  const rps = (requests.values.count / seconds).toFixed(1).padStart(7);
  const errors = `${(failed.values.rate * 100).toFixed(2)}%`.padStart(8);
  return `${label.padEnd(8)}${rps}${ms(d["p(50)"])}${ms(d["p(95)"])}${ms(d["p(99)"])}${errors}`;
}

export function handleSummary(data) {
  const m = data.metrics;
  const lines = [`${"stage".padEnd(8)}${"rps".padStart(7)}${"p50".padStart(8)}${"p95".padStart(8)}${"p99".padStart(8)}${"errors".padStart(8)}`];
  for (const rate of RATES) {
    const stage = stageName(rate);
    lines.push(
      row(stage, m[`http_req_duration{stage:${stage}}`], m[`http_reqs{stage:${stage}}`], m[`http_req_failed{stage:${stage}}`], STAGE_SECONDS),
    );
  }
  if (NL) {
    lines.push(row("nl", m["http_req_duration{scenario:nl}"], m["http_reqs{scenario:nl}"], m["http_req_failed{scenario:nl}"], 60));
  }
  return {
    [SUMMARY]: JSON.stringify(data, null, 2),
    stdout: `\n${lines.join("\n")}\n`,
  };
}
