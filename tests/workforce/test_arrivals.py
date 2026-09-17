from app.workforce.arrivals import generate_arrivals, InjectedEvent

# Test 1 : une heure de simulation, sans incident
arrivals = generate_arrivals(duration_minutes=60, start_hour=8, rng_seed=42)
print(f"Nombre de clients arrivés en 1h (8h-9h) : {len(arrivals)}")
print("Les 3 premiers clients :")
for a in arrivals[:3]:
    print(a)

# Test 2 : reproductibilité — relancer avec la même seed doit donner EXACTEMENT le même résultat
arrivals_bis = generate_arrivals(duration_minutes=60, start_hour=8, rng_seed=42)
print("\nLes deux listes sont-elles identiques ?", arrivals == arrivals_bis)

# Test 3 : avec un incident réseau à t=20min pendant 30min (x4 le lambda)
incident = InjectedEvent(start_time=20, duration=30, multiplier=4.0)
arrivals_incident = generate_arrivals(
    duration_minutes=60, start_hour=8, events=[incident], rng_seed=42
)
print(f"\nNombre de clients SANS incident : {len(arrivals)}")
print(f"Nombre de clients AVEC incident : {len(arrivals_incident)}")