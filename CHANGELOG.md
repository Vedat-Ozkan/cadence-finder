# Changelog

## 2026-11-17: merged

- Searches with many steps joined by gaps now finish in seconds instead of minutes (a 26-step query went from 32.8 s to 3.0 s).

## 2026-11-14: merged

- The search tool says that a limit ends a page on a whole movement, not at an exact count.

## 2026-11-13: merged

- Public paths that cost money are now limited: `/passages` has a rate limit, and the wake paths and their concurrency are capped.
- A monthly budget is now set up in Terraform.
- Search skips a movement left half written by a failed ingest instead of failing.

## 2026-11-05: deployed

- All 40 corpora, 1,284 movements, are searchable on the live service.
- The live server's disk is 20 GB, because new software would not fit on the old 10 GB.

## 2026-10-27: merged

- Claude can search Cadence Finder as a connector, with one tool, `search`.
- Searches in Claude answer at once, even while the server is stopped.
- Chord labels as Sheet Music Analysis writes them, such as `Cad64`, `Ger65` and `viiø7`, are accepted.
- The search tool says that other composers are not covered.

## 2026-10-24: deployed

- First live deployment, at https://cadence-finder.duckdns.org.
- Added similar-passage search, plain-English search, and questions answered with checked citations.
- The server stops after 30 idle minutes and starts when someone opens the wake link, which shows a progress page while it starts.
- Alarms and a dashboard now watch response times and failed ingests.

## Before 2026-10-24

- Search for chord progressions and cadences, such as `Ger ... @PAC`, over three corpora (Beethoven and Mozart among them).
