import http from "k6/http";
import { check, sleep } from "k6";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";

const QUERIES = [
  { q: "I V I IV6", corpus: "abc" },
  { q: "Ger ... @PAC", corpus: "bps" },
  { q: "It6 V", corpus: "mps" },
  { q: "V65/V V", corpus: "bps" },
  { q: "ii6 V@HC", corpus: "mps" },
  { q: "Fr6 V", corpus: "abc" },
];

export const options = {
  vus: 3,
  duration: "30s",
  thresholds: {
    http_req_failed: ["rate<0.01"],
    checks: ["rate>0.99"],
  },
};

export default function () {
  const query = QUERIES[__ITER % QUERIES.length];
  const search = http.get(`${BASE_URL}/search?q=${encodeURIComponent(query.q)}&corpus=${query.corpus}&limit=50`, {
    tags: { name: "search" },
  });
  check(search, { "search 200": (r) => r.status === 200 });

  const similar = http.get(`${BASE_URL}/passages/${encodeURIComponent("bps:01-1#0")}/similar?k=5&rerank=false`, {
    tags: { name: "similar" },
  });
  check(similar, { "similar 200": (r) => r.status === 200 });

  sleep(1);
}
