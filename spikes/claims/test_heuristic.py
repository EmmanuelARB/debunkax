"""Mirrors app/src/test/java/app/debunkax/domain/HeuristicClaimDetectorTest.kt exactly,
to check the Python port in heuristic.py behaves the same as the Kotlin original."""
from heuristic import is_claim


def test_narco_trafic_operations_figure_is_a_claim():
    assert is_claim(
        "LES OPÉRATIONS CONTRE LE NARCO TRAFIC ONT AUGMENTÉ VINGT QUATRE POURCENT ENTRE "
        "DEUX MILLE VINGT QUATRE ET DEUX MILLE VINGT CINQ DANS L'ENSEMBLE DES ALPES MARITIMES"
    )


def test_unaccompanied_minors_count_is_a_claim():
    assert is_claim("LES MINEURS NON ACCOMPAGNÉS IL Y EN A AUJOURD'HUI DEUX MILLE")


def test_cost_per_unit_is_a_claim():
    assert is_claim("QUI CHACUN COÛTE QUARANTE MILLE EUROS")


def test_debt_has_exploded_is_a_claim():
    assert is_claim("LA DETTE A EXPLOSÉ SOUS CE GOUVERNEMENT")


def test_courtesy_address_is_not_a_claim():
    assert not is_claim("MERCI MADAME LA PRÉSIDENTE MONSIEUR LE DÉPUTÉ")


def test_opinion_without_a_figure_is_not_a_claim():
    assert not is_claim("JE PENSE QU'IL FAUT RENFORCER LA POLICE")


def test_admonition_is_not_a_claim():
    assert not is_claim("VOUS DEVRIEZ AVOIR HONTE")


def test_fewer_than_6_words_is_not_a_claim():
    assert not is_claim("LE CHÔMAGE AUGMENTE ENCORE")


def test_a_sentence_ending_in_a_question_mark_is_not_a_claim():
    assert not is_claim("LE CHÔMAGE A T IL VRAIMENT BAISSÉ DE DIX POURCENT ?")
