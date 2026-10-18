"""
Relance les deux cas qui avaient plante hier a cause du bug
check_system_status (maintenant corrige).
"""
import asyncio

from scripts.evaluate_tool_precision import CASES, run_case
from app.quality.tool_precision import summarize

TARGET_NAMES = {"geste_commercial_frais", "panne_application"}


async def main():
    cases = [c for c in CASES if c.name in TARGET_NAMES]

    results = []
    for case in cases:
        print(f"Running: {case.name}...")
        result = await run_case(case)
        status = "OK" if result.recall_hit else "MISS"
        print(f"  [{status}] attendu={result.expected_tools} appele={result.called_tools} precision={result.precision:.2f}")
        results.append(result)

    print()
    summary = summarize(results)
    print(f"Cas evalues : {summary['n_cases']}")
    print(f"Rappel : {summary['recall']:.1%}")
    print(f"Precision moyenne : {summary['avg_precision']:.1%}")


asyncio.run(main())