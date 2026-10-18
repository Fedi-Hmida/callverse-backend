from app.workforce.arrivals import generate_arrivals, InjectedEvent


def test_arrivals_are_generated():
    """Vérifie qu'une simulation d'une heure génère bien des arrivées."""
    arrivals = generate_arrivals(duration_minutes=60, start_hour=8, rng_seed=42)
    print(f"Nombre de clients générés : {len(arrivals)}")
    assert len(arrivals) > 0
    