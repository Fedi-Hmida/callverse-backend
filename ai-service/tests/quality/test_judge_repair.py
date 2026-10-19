"""
La réparation de la sortie du juge corrige la forme (balises, arrondis), jamais le fond :
un score ou une preuve absents ne sont pas inventés, l'évaluation est rejetée (502).
"""
import json

import pytest

from app.contracts.quality import CODES
from app.quality.judge import _repair_and_validate_judge_result
from tests.quality.test_judge import request, result


def _raw(mutate=None) -> str:
    data = result().model_dump()
    if mutate:
        mutate(data)
    return json.dumps(data, ensure_ascii=False)


def test_la_forme_est_reparee_balises_et_score_decimal():
    def decimal_score(data):
        data["scores"]["empathy"] = 7.6

    repaired = _repair_and_validate_judge_result("```json\n" + _raw(decimal_score) + "\n```", request())

    assert repaired.scores["empathy"] == 8


def test_un_score_absent_n_est_pas_invente():
    def drop(data):
        del data["scores"]["empathy"]

    with pytest.raises(ValueError, match="empathy"):
        _repair_and_validate_judge_result("```json\n" + _raw(drop) + "\n```", request())


def test_un_score_illisible_n_est_pas_remplace_par_5():
    def garble(data):
        data["scores"]["accuracy"] = "bonne"

    with pytest.raises(ValueError, match="accuracy"):
        _repair_and_validate_judge_result("```json\n" + _raw(garble) + "\n```", request())


def test_une_preuve_absente_n_est_pas_fabriquee():
    def drop(data):
        data["evidence"] = [e for e in data["evidence"] if e["criterion"] != "compliance"]

    with pytest.raises(ValueError, match="compliance"):
        _repair_and_validate_judge_result("```json\n" + _raw(drop) + "\n```", request())


def test_un_index_de_message_invalide_n_est_pas_redirige():
    def bad_index(data):
        data["evidence"][0]["message_index"] = 42

    with pytest.raises(ValueError, match="message_index"):
        _repair_and_validate_judge_result("```json\n" + _raw(bad_index) + "\n```", request())


@pytest.mark.parametrize("bad_index", [0, 42, "x"])
def test_une_affirmation_mal_situee_est_ecartee_sans_planter(bad_index):
    # 0 = message CUSTOMER : une affirmation ne peut venir que du conseiller.
    def misplaced_claim(data):
        data["claims"][0]["message_index"] = bad_index

    repaired = _repair_and_validate_judge_result("```json\n" + _raw(misplaced_claim) + "\n```", request())

    assert repaired.claims == []


def test_tous_les_criteres_restent_requis():
    assert set(CODES) == {"relevance", "accuracy", "compliance", "communication", "empathy", "resolution"}


@pytest.mark.parametrize('value', [100, 11, -1, True, float('inf')])
def test_out_of_range_scores_are_never_clamped_to_a_perfect_grade(value):
    def wrong_scale(data):
        data['scores']['accuracy'] = value
    with pytest.raises(ValueError, match='accuracy'):
        _repair_and_validate_judge_result(_raw(wrong_scale), request())
