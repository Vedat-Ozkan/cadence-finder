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
fetch bach_en_fr_suites bach_en_fr_suites 9cd6d362ed8246ed8edfc425944862ed88ddf1a5
fetch bach_solo bach_solo dce67f753ced46b43dbfb16779fe3a19233893da
fetch bartok_bagatelles bartok_bagatelles c6221f6ecb4dbcd476e827f6bf8705bdcb15c8a9
fetch beethoven_piano_sonatas beethoven_piano_sonatas ea7181bff88abc8713257234f7ec4033178c57a9
fetch c_schumann_lieder c_schumann_lieder 9ed9255559c670a79a76bc1b21a911b16efe492d
fetch chopin_mazurkas chopin_mazurkas 5931135e614985023b96de2a291c74b7ef90b287
fetch corelli corelli 57d37305a31d3ff7e4be298edb1f6d0eb010447d
fetch couperin_clavecin couperin_clavecin 3bd00fc56d3473b26f1bea5fe50d1a1f2458c462
fetch couperin_concerts couperin_concerts 49efcdd24c39d48bf009d60f8aa3bbc1a1d9713a
fetch cpe_bach_keyboard cpe_bach_keyboard 4b3511eab12b5fbfc7aa5c75819ee02225430da8
fetch debussy_suite_bergamasque debussy_suite_bergamasque 322ece590e536924308a551a69d9c1520248d3d5
fetch dvorak_silhouettes dvorak_silhouettes f228006fcd8696c809cfc8e701ed215cec3d07f1
fetch frescobaldi_fiori_musicali frescobaldi_fiori_musicali e17de917111e900929db785bfa2d0313968ca5d4
fetch grieg_lyric_pieces grieg_lyric_pieces 91a304563521f3f273b8c0aadec1ce2ede2d1384
fetch handel_keyboard handel_keyboard d3b42765e0d2457b6abb1bdb3e8dd622db61b084
fetch jc_bach_sonatas jc_bach_sonatas ac9fd07905eb62c3d8cfbd96811491170a216232
fetch kleine_geistliche_konzerte kleine_geistliche_konzerte b3cc43d4ffc9a141b85400ab460aaeb95c29ea4a
fetch kozeluh_sonatas kozeluh_sonatas 23c1983a48809647af1a08d54b1fade39ec93995
fetch liszt_pelerinage liszt_pelerinage f1cfd308adba5763aad3a18885eac48d42449fc4
fetch mahler_kindertotenlieder mahler_kindertotenlieder 9122b6d313b94185ad6a42710ad09b7c59d31af5
fetch medtner_tales medtner_tales 1d2e58ba8d329463829e45e75900af43be4256bf
fetch mendelssohn_quartets mendelssohn_quartets b92a90c5ce2423a3f0d32536dc5c1304fe6d0369
fetch monteverdi_madrigals monteverdi_madrigals 6e1adc73a865b50fb8b8f38661f06c4fad8c2b53
fetch mozart_piano_sonatas mozart_piano_sonatas 5337257a5318711e6302cfe85c3f1a6ade3c6271
fetch pergolesi_stabat_mater pergolesi_stabat_mater b24d5432884d641ba98cf49b107c47d199d38100
fetch peri_euridice peri_euridice f02fc6643ac489776aaed5418c1109e88381648f
fetch pleyel_quartets pleyel_quartets 8b3d7f5e966290631571274067de6d3a9206a737
fetch poulenc_mouvements_perpetuels poulenc_mouvements_perpetuels 7793981bf4bc9dbcc1d14de4c17abb4cb412ce4b
fetch rachmaninoff_piano rachmaninoff_piano a73f3246a764215863000357c81309b210a43f15
fetch ravel_piano ravel_piano 5a97ccee5383a87dc38688733a59dba499ef44d9
fetch scarlatti_sonatas scarlatti_sonatas 7750a6086db69c48e5e65f71565d24bd0f68513a
fetch schubert_winterreise schubert_winterreise da2e281eec9bbcf8ae1e981f63f913a1a99b5edb
fetch schulhoff_suite_dansante_en_jazz schulhoff_suite_dansante_en_jazz e558f2d2505dcdf827b4212e87fda875e0440908
fetch schumann_kinderszenen schumann_kinderszenen ee929c1556bc937fe1ea7303cac4476e37caa4d1
fetch schumann_liederkreis schumann_liederkreis 226b788546e0b5c4907b996cb35f94bb4a38e980
fetch sweelinck_keyboard sweelinck_keyboard 0c2a4f5b613e44b6ab06e876d1f0d9afb9f1d445
fetch tchaikovsky_seasons tchaikovsky_seasons 5af15033c5f9c282f38fcf71234b86349e61e8c3
fetch wagner_overtures wagner_overtures fe316b6ce8ba1e5f5ee01a01b323f3d13a876382
fetch wf_bach_sonatas wf_bach_sonatas 379e50dd389dbdbcf77f5eb88ecac7841c7ed0e4

curl -fsSL -o "$root/distant_listening_corpus.metadata.tsv" \
  https://raw.githubusercontent.com/DCMLab/distant_listening_corpus/12be0d3ff7c6e0ef67c7d4e8e44541e5f288f39d/distant_listening_corpus.metadata.tsv
