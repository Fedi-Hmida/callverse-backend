from types import SimpleNamespace
import pytest
from app.quality.sourcing_check import align_reference_indices, validate_references


def test_french_punctuation_spacing_does_not_invalidate_an_exact_quote():
    request = SimpleNamespace(messages=[SimpleNamespace(content="Bonne journée ! Tout va bien ?", sender="ADVISOR")], source_documents=[])
    result = SimpleNamespace(evidence=[SimpleNamespace(message_index=0, evidence="Bonne journée! Tout va bien?")], claims=[])
    validate_references(request, result)


def test_typography_normalization_still_rejects_changed_words():
    request = SimpleNamespace(messages=[SimpleNamespace(content="Votre demande est en cours !", sender="ADVISOR")], source_documents=[])
    result = SimpleNamespace(evidence=[SimpleNamespace(message_index=0, evidence="Votre demande est terminée!")], claims=[])
    with pytest.raises(ValueError, match="extrait"):
        validate_references(request, result)


def test_wrong_index_is_aligned_only_to_a_unique_real_quote():
    request = SimpleNamespace(messages=[SimpleNamespace(content="Je souhaite de l'aide.", sender="CUSTOMER"), SimpleNamespace(content="Je suis à votre écoute.", sender="ADVISOR")], source_documents=[])
    evidence = SimpleNamespace(message_index=1, evidence="Je souhaite de l'aide.")
    result = SimpleNamespace(evidence=[evidence], claims=[])
    align_reference_indices(request, result)
    assert evidence.message_index == 0
    validate_references(request, result)


def test_ambiguous_quote_is_not_silently_reassigned():
    request = SimpleNamespace(messages=[SimpleNamespace(content="Bonjour", sender="CUSTOMER"), SimpleNamespace(content="Bonjour", sender="ADVISOR"), SimpleNamespace(content="Autre texte", sender="ADVISOR")], source_documents=[])
    evidence = SimpleNamespace(message_index=2, evidence="Bonjour")
    result = SimpleNamespace(evidence=[evidence], claims=[])
    align_reference_indices(request, result)
    assert evidence.message_index == 2
    with pytest.raises(ValueError, match="extrait"):
        validate_references(request, result)


def test_claim_cannot_be_aligned_to_customer_text():
    request = SimpleNamespace(messages=[SimpleNamespace(content="Ma demande est résolue.", sender="CUSTOMER"), SimpleNamespace(content="Merci.", sender="ADVISOR")], source_documents=[])
    claim = SimpleNamespace(message_index=1, excerpt="Ma demande est résolue.", source_keys=[], status="UNVERIFIABLE")
    result = SimpleNamespace(evidence=[], claims=[claim])
    align_reference_indices(request, result)
    assert claim.message_index == 1
    with pytest.raises(ValueError, match="extrait"):
        validate_references(request, result)
