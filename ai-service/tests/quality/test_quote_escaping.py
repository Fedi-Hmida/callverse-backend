from app.quality.sourcing_check import _normalize


def test_double_escaped_whitespace_is_equivalent_to_actual_line_breaks():
    assert _normalize("Bonjour,\\n\\nVoici la demande.\\tMerci.") == _normalize("Bonjour,\n\nVoici la demande.\tMerci.")


def test_whitespace_repair_does_not_accept_changed_words():
    assert _normalize("Carte\\nnon bloquée") != _normalize("Carte\nbloquée")
