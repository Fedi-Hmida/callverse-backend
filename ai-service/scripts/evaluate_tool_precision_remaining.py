"""
Reprend l'evaluation de precision d'outils la ou elle s'est arretee
hier (cas 6 a 12), pour ne pas re-consommer de tokens sur les 5
premiers deja evalues.
"""
import asyncio

from scripts.evaluate_tool_precision import CASES, run_case
from app.quality.tool_precision import summarize


async def main():
    remaining = CASES[5:]  # demande_credit -> reclamation_generale

    results = []
    for case in remaining:
        print(f"Running: {case.name}...")
        result = await run_case(case)
        status = "OK" if result.recall_hit else "MISS"
        print(f"  [{status}] attendu={result.expected_tools} appele={result.called_tools} precision={result.precision:.2f}")
        results.append(result)

    print()
    print("--- Resume (cas 6 a 12) ---")
    summary = summarize(results)
    print(f"Cas evalues      : {summary['n_cases']}")
    print(f"Rappel           : {summary['recall']:.1%}")
    print(f"Precision moyenne: {summary['avg_precision']:.1%}")


asyncio.run(main())