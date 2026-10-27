import csv
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CORPUS = ROOT / "corpus"
OUT = ROOT / "corpus-core/src/main/resources/cadencefinder/corpus"
SUFFIX = ".harmonies.tsv"
IDS = {"ABC": "abc", "beethoven_piano_sonatas": "bps", "mozart_piano_sonatas": "mps"}
DIRECTORIES = {"ABC": "abc"}
NAMES = {
    "ABC": "Beethoven string quartets",
    "bach_en_fr_suites": "Bach, English and French Suites",
    "bach_solo": "Bach, solo violin, cello and flute works",
    "bartok_bagatelles": "Bartók, 14 Bagatelles, Op. 6",
    "beethoven_piano_sonatas": "Beethoven piano sonatas",
    "c_schumann_lieder": "Clara Schumann, songs",
    "chopin_mazurkas": "Chopin, mazurkas",
    "corelli": "Corelli, trio sonatas, Opp. 1, 3 and 4",
    "couperin_clavecin": "François Couperin, L'Art de toucher le clavecin",
    "couperin_concerts": "François Couperin, Concerts royaux, Nouveaux concerts and Le Parnasse",
    "cpe_bach_keyboard": "C. P. E. Bach, keyboard sonatas and fantasias",
    "debussy_suite_bergamasque": "Debussy, Suite bergamasque",
    "dvorak_silhouettes": "Dvořák, Silhouettes, Op. 8",
    "frescobaldi_fiori_musicali": "Frescobaldi, Fiori musicali",
    "grieg_lyric_pieces": "Grieg, Lyric Pieces",
    "handel_keyboard": "Handel, The Harmonious Blacksmith",
    "jc_bach_sonatas": "J. C. Bach, keyboard sonatas",
    "kleine_geistliche_konzerte": "Schütz, Kleine geistliche Konzerte",
    "kozeluh_sonatas": "Koželuch, keyboard sonatas",
    "liszt_pelerinage": "Liszt, Années de pèlerinage",
    "mahler_kindertotenlieder": "Mahler, Kindertotenlieder",
    "medtner_tales": "Medtner, Tales",
    "mendelssohn_quartets": "Mendelssohn, string quartets",
    "monteverdi_madrigals": "Monteverdi, madrigals and psalms",
    "mozart_piano_sonatas": "Mozart piano sonatas",
    "pergolesi_stabat_mater": "Pergolesi, Stabat Mater",
    "peri_euridice": "Peri, Euridice",
    "pleyel_quartets": "Pleyel, string quartets",
    "poulenc_mouvements_perpetuels": "Poulenc, Trois mouvements perpétuels",
    "rachmaninoff_piano": "Rachmaninoff, Variations on a Theme of Corelli",
    "ravel_piano": "Ravel, Jeux d'eau and Miroirs",
    "scarlatti_sonatas": "Domenico Scarlatti, keyboard sonatas",
    "schubert_winterreise": "Schubert, Winterreise",
    "schulhoff_suite_dansante_en_jazz": "Schulhoff, Suite dansante en jazz",
    "schumann_kinderszenen": "Robert Schumann, Kinderszenen",
    "schumann_liederkreis": "Robert Schumann, Liederkreis, Op. 39",
    "sweelinck_keyboard": "Sweelinck, Fantasia cromatica",
    "tchaikovsky_seasons": "Tchaikovsky, The Seasons",
    "wagner_overtures": "Wagner, preludes to Tristan und Isolde and Die Meistersinger",
    "wf_bach_sonatas": "W. F. Bach, keyboard sonatas",
}


def fixed_title(corpus, stem):
    if corpus == "ABC":
        m = re.fullmatch(r"n(\d+)op(\d+)(?:-(\d+))?_(\d+)", stem)
        number = f" No. {m[3]}" if m[3] else ""
        return f"String Quartet No. {int(m[1])}, Op. {m[2]}{number}, mvt {int(m[4])}"
    if corpus == "beethoven_piano_sonatas":
        m = re.fullmatch(r"(\d+)-(\d+)", stem)
        return f"Piano Sonata No. {int(m[1])}, mvt {int(m[2])}"
    if corpus == "mozart_piano_sonatas":
        m = re.fullmatch(r"K(\d+)-(\d+)", stem)
        return f"Piano Sonata K. {m[1]}, mvt {int(m[2])}"
    return None


def title(row, stem, works):
    if not row:
        return stem.replace("_", " ")
    work = row["workTitle"].strip()
    full = row["title_text"].strip()
    if full.startswith(work) and len(full) > len(work) and "\n" not in full and "\\n" not in full and "<" not in full:
        work = full
    movement = row["movementTitle"].strip()
    if movement and movement not in work:
        return f"{work}, {movement}" if work else movement
    if row["movementNumber"].strip() and works[row["workTitle"]] > 1:
        return f"{work}, No. {row['movementNumber'].strip()}"
    return work or stem.replace("_", " ")


def main():
    metadata = {}
    with open(CORPUS / "distant_listening_corpus.metadata.tsv", newline="") as file:
        for row in csv.DictReader(file, delimiter="\t"):
            metadata[row["corpus"], row["piece"]] = row
    corpora, titles = [], []
    for corpus in sorted({corpus for corpus, _ in metadata}):
        directory = DIRECTORIES.get(corpus, corpus)
        files = sorted((CORPUS / directory / "harmonies").glob("*" + SUFFIX))
        if not files:
            sys.exit(f"no harmonies for {corpus} under {CORPUS / directory}")
        stems = [f.name[: -len(SUFFIX)] for f in files]
        rows = [metadata.get((corpus, stem)) for stem in stems]
        works = {}
        for row in rows:
            if row:
                works[row["workTitle"]] = works.get(row["workTitle"], 0) + 1
        has_cadences = any(
            harmony["cadence"] for f in files for harmony in csv.DictReader(open(f, newline=""), delimiter="\t"))
        composer = next(row["composer"].strip() for row in rows if row)
        corpus_id = IDS.get(corpus, corpus)
        corpora.append((corpus_id, directory, composer, NAMES[corpus], str(has_cadences).lower()))
        for stem, row in zip(stems, rows):
            titles.append((corpus_id, stem, fixed_title(corpus, stem) or title(row, stem, works)))
    OUT.mkdir(parents=True, exist_ok=True)
    for path, rows in ((OUT / "corpora.tsv", corpora), (OUT / "titles.tsv", titles), (ROOT / "wake/corpora.tsv", corpora)):
        with open(path, "w", newline="") as file:
            csv.writer(file, delimiter="\t", lineterminator="\n", quoting=csv.QUOTE_NONE).writerows(rows)
    print(f"{len(corpora)} corpora, {len(titles)} movements")


main()
