from app.simulator.profiles import PROFILES
from app.simulator.prompts import build_system_prompt


def test_six_profiles_present():
    assert set(PROFILES.keys()) == {
        "calme", "impatient", "insatisfait", "exigeant", "offre", "churn"
    }


def test_churn_profile_has_highest_decay():
    decays = {k: v.patience_decay_base for k, v in PROFILES.items()}
    assert max(decays, key=decays.get) == "churn"


def test_build_system_prompt_contains_objective():
    prompt = build_system_prompt("impatient", "faire opposition sur une carte", 40, 30)
    assert "faire opposition sur une carte" in prompt
    assert "impatient" in prompt.lower() or "Client impatient" in prompt
