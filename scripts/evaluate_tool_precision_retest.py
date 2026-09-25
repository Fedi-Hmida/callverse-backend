"""
Retest apres ajustement du prompt (ajout regles 4, 5, 6) sur les trois
cas identifies comme MISS : geste_commercial_frais, cloture_compte,
phishing_signale. Compare au resultat "avant" documente.
"""
import asyncio
import logging

from scripts.evaluate_tool_precision import CASES, run_case
from app.quality.tool_precision import summarize

logging.basicConfig(level=logging.WARNING, format="%(levelname)s %(name)s: %(message)s", force=True)

TARGET_NAMES = {"phishing_signale"}

RESULTATS_AVANT = {
    "phishing_signale": "MISS apres 1er ajustement (appele=set(), filet de securite declenche)",
}


async def main():
    cases = [c for c in CASES if c.name in TARGET_NAMES]

    results = []
    for case in cases:
        print(f"Running: {case.name}...")
        print(f"  Avant: {RESULTATS_AVANT[case.name]}")
        result = await run_case(case)
        status = "OK" if result.recall_hit else "MISS"
        print(f"  Apres: [{status}] attendu={result.expected_tools} appele={result.called_tools} precision={result.precision:.2f}")
        results.append(result)

    summary = summarize(results)
    print()
    print("--- Resume ---")
    print(f"Cas evalues : {summary['n_cases']}")
    print(f"Rappel : {summary['recall']:.1%}")


asyncio.run(main())