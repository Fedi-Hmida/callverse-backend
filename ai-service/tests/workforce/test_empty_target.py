import numpy as np
from app.contracts.workforce import WorkforceState
from app.workforce import policy


def state(queues=None, available=None):
    return WorkforceState(queues=queues or {'ACCOUNTS':0,'CARDS':0,'CREDIT':0,'FRAUD':1},
        avg_wait={'ACCOUNTS':0,'CARDS':0,'CREDIT':0,'FRAUD':43559},
        available=available or {'ACCOUNTS':1,'CARDS':0,'CREDIT':0,'FRAUD':0},
        sla_today=1,hour=10,trend=1)


def model_selecting(monkeypatch, action):
    class Model:
        def predict(self, observation, deterministic):
            return np.array(action), None
    monkeypatch.setattr(policy,'load_policy',lambda _:Model())


def test_empty_credit_target_does_not_imply_no_capacity_for_the_waiting_fraud_queue(monkeypatch):
    model_selecting(monkeypatch,3)
    result=policy.decide(state())
    assert result.type=='NONE'
    assert result.from_pool is None and result.to_pool is None
    assert 'Crédit est vide' in result.reason
    assert 'superviseur' in result.reason
    assert 'aucun conseiller' not in result.reason


def test_no_waiting_contacts_has_a_different_explanation(monkeypatch):
    model_selecting(monkeypatch,3)
    result=policy.decide(state(queues={'ACCOUNTS':0,'CARDS':0,'CREDIT':0,'FRAUD':0}))
    assert result.type=='NONE'
    assert 'Aucun contact en attente' in result.reason


def test_no_available_donor_is_explained_only_for_a_nonempty_target(monkeypatch):
    model_selecting(monkeypatch,4)
    result=policy.decide(state(available={'ACCOUNTS':0,'CARDS':0,'CREDIT':0,'FRAUD':0}))
    assert result.type=='NONE'
    assert 'Fraude' in result.reason
    assert 'aucun conseiller' in result.reason
