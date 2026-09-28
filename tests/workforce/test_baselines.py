from app.workforce.baselines import static_fifo, threshold


def make_state(queues, available):
    """Petite fonction utilitaire pour construire un state de test rapidement."""
    return {
        "queues": queues,
        "avg_wait": {},
        "available": available,
        "sla_today": 0.71,
        "hour": 11,
        "trend": 1.0,
    }


def test_static_fifo_never_reassigns():
    """STATIC_FIFO ne doit JAMAIS proposer de réaffectation, peu importe l'état."""
    state = make_state(
        queues={"ACCOUNT": 50, "CARD": 0, "CREDIT": 0, "ADVISORY": 0},
        available={"ACCOUNT": 0, "CARD": 5, "CREDIT": 5, "ADVISORY": 5},
    )
    result = static_fifo(state)
    assert result["action"]["type"] == "NONE"


def test_threshold_does_nothing_below_limit():
    """Si aucune file ne dépasse le seuil, THRESHOLD ne doit rien faire."""
    state = make_state(
        queues={"ACCOUNT": 5, "CARD": 3, "CREDIT": 1, "ADVISORY": 2},
        available={"ACCOUNT": 1, "CARD": 3, "CREDIT": 2, "ADVISORY": 1},
    )
    result = threshold(state, n=10)
    assert result["action"]["type"] == "NONE"


def test_threshold_reassigns_when_queue_exceeds_limit():
    """Si une file dépasse le seuil, THRESHOLD doit proposer une réaffectation
    depuis la compétence qui a le plus de conseillers disponibles."""
    state = make_state(
        queues={"ACCOUNT": 12, "CARD": 3, "CREDIT": 1, "ADVISORY": 2},
        available={"ACCOUNT": 0, "CARD": 3, "CREDIT": 2, "ADVISORY": 1},
    )
    result = threshold(state, n=10)

    assert result["action"]["type"] == "REASSIGN"
    assert result["action"]["to_pool"] == "ACCOUNT"      # la file surchargée
    assert result["action"]["from_pool"] == "CARD"        # celle qui a le plus de dispo


def test_threshold_does_nothing_if_no_one_available_to_donate():
    """Si la file est surchargée mais qu'AUCUN conseiller n'est libre
    ailleurs, THRESHOLD ne peut rien proposer."""
    state = make_state(
        queues={"ACCOUNT": 15, "CARD": 0, "CREDIT": 0, "ADVISORY": 0},
        available={"ACCOUNT": 0, "CARD": 0, "CREDIT": 0, "ADVISORY": 0},
    )
    result = threshold(state, n=10)
    assert result["action"]["type"] == "NONE"