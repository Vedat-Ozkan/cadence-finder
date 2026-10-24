#!/bin/bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)/corpus"

fetch() {
  local name=$1 repo=$2 commit=$3 dir="$root/$1"
  if [ "$(git -C "$dir" rev-parse HEAD 2>/dev/null)" = "$commit" ]; then
    return
  fi
  rm -rf "$dir"
  git init -q "$dir"
  git -C "$dir" remote add origin "https://github.com/DCMLab/$repo.git"
  git -C "$dir" sparse-checkout set --no-cone '/harmonies/*.tsv' '/CITATION.cff' '/LICENSE'
  git -C "$dir" fetch -q --depth 1 --filter=blob:none origin "$commit"
  git -C "$dir" checkout -q FETCH_HEAD
  echo "$name: $(ls "$dir"/harmonies/*.tsv | wc -l) movements at $commit"
}

fetch abc ABC b6b7d38500bacb30c81db7e09d8790df1a2edd46
fetch beethoven_piano_sonatas beethoven_piano_sonatas ea7181bff88abc8713257234f7ec4033178c57a9
fetch mozart_piano_sonatas mozart_piano_sonatas 5337257a5318711e6302cfe85c3f1a6ade3c6271
