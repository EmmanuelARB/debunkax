"""Exact Python port of app/src/main/java/app/debunkax/domain/ClaimDetector.kt
(HeuristicClaimDetector) and the normalize() function it depends on from
app/src/main/java/app/debunkax/domain/SessionRules.kt (RepeatedClaimMatcher.normalize).

Kept in lockstep with the Kotlin source; ported by hand rather than run through a JVM
harness (no JVM/Kotlin toolchain in this Python-only spike). Verified against every
example in app/src/test/java/app/debunkax/domain/HeuristicClaimDetectorTest.kt, see
test_heuristic.py.
"""
import re
import unicodedata

_COMBINING = re.compile(r"[̀-ͯ]+")
_APOSTROPHES = re.compile(r"[’']")
_THOUSANDS_SEP = re.compile(r"(?<=\d)[\s.](?=\d{3}\b)")
_NON_ALNUM_PCT_COMMA = re.compile(r"[^a-z0-9%,]+")
_STRAY_COMMA = re.compile(r"(?<!\d),|,(?!\d)")
_WHITESPACE = re.compile(r"\s+")


def normalize(text: str) -> str:
    """Port of RepeatedClaimMatcher.normalize (SessionRules.kt)."""
    lowered = text.lower()
    nfd = unicodedata.normalize("NFD", lowered)
    no_marks = _COMBINING.sub("", nfd)
    no_apostrophes = _APOSTROPHES.sub(" ", no_marks)
    no_thousands_sep = _THOUSANDS_SEP.sub("", no_apostrophes)
    # Kotlin's [^\p{L}\p{N}%,]+ : keep unicode letters/digits/%/, drop everything else.
    # After NFD+mark-stripping, remaining letters are ASCII, so this ASCII charset
    # matches \p{L}\p{N} here; '%' and ',' are also kept.
    cleaned = _NON_ALNUM_PCT_COMMA.sub(" ", no_thousands_sep)
    no_stray_comma = _STRAY_COMMA.sub(" ", cleaned)
    collapsed = _WHITESPACE.sub(" ", no_stray_comma.strip())
    return collapsed


FIGURE = re.compile(
    r"\d|%|\bpour ?cent\b|\b(million|milliard|millier|centaine|dizaine)s?\b|"
    r"\b(moitie|tiers|quart|double|triple)\b|"
    r"\b(deux|trois|quatre|cinq|six|sept|huit|neuf|dix|vingt|trente|quarante|cinquante|cent|mille)\b"
)

FACTUAL_CUE = re.compile(
    r"\b(augment|baiss|diminu|progress|recul|explos|chut|doubl|tripl|multipli)\w*|"
    r"\b(record|jamais|premier|premiere|dernier|seul|seule|aucun|aucune|tous les|toutes les|la plupart)\b|"
    r"\b(plus|moins) (de|que)\b|\b(depuis|en) (19|20)\d\d\b|"
    r"\b(loi|decret|constitution|traite|directive|rapport|etude|selon|chiffres?)\b|"
    r"\b(chomage|inflation|dette|deficit|impots?|taxes?|salaires?|retraites?|immigration|immigres|"
    r"criminalite|delinquance|pib|croissance|pouvoir d achat)\b"
)

OPINION = re.compile(
    r"\b(je pense|je crois|a mon avis|selon moi|il faut|il faudrait|je souhaite|je veux|"
    r"nous voulons|je propose|j espere)\b"
)


def is_claim(sentence: str) -> bool:
    """Exact port of HeuristicClaimDetector.isClaim (ClaimDetector.kt)."""
    text = normalize(sentence)
    words = [w for w in text.split(" ") if w]
    if len(words) < 6 or sentence.rstrip().endswith("?"):
        return False
    has_figure = FIGURE.search(text) is not None
    if OPINION.search(text) and not has_figure:
        return False
    return has_figure or FACTUAL_CUE.search(text) is not None
