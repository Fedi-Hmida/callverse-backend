import pytest

from app.quality.rubric import RUBRIC, compute_global_score, validate_rubric


def make_scores(value=0, **overrides):
    """Construit un dict de notes : toutes à `value`, sauf celles données en override."""
    scores = {c.code: value for c in RUBRIC}
    scores.update(overrides)
    return scores


def test_weights_sum_to_one():
    validate_rubric()  # lève une ValueError si la somme est fausse


def test_rubric_has_six_criteria():
    assert len(RUBRIC) == 6


def test_uniform_scores_give_same_global_score():
    assert compute_global_score(make_scores(8)) == pytest.approx(8.0)


def test_accuracy_weighs_more_than_empathy():
    high_accuracy = compute_global_score(make_scores(0, accuracy=10))
    high_empathy = compute_global_score(make_scores(0, empathy=10))
    assert high_accuracy > high_empathy


def test_missing_criterion_raises():
    scores = make_scores(5)
    del scores["empathy"]
    with pytest.raises(ValueError):
        compute_global_score(scores)


def test_out_of_range_score_raises():
    with pytest.raises(ValueError):
        compute_global_score(make_scores(5, accuracy=11))