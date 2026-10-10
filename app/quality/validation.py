"""Accord humain/IA sur des paires de notes, sans dépendance supplémentaire."""
import math
from app.quality.rubric import RUBRIC, compute_global_score

def pearson(left: list[float], right: list[float]) -> float | None:
    if len(left) != len(right) or len(left) < 2:
        raise ValueError("Au moins deux paires sont nécessaires.")
    a, b = sum(left) / len(left), sum(right) / len(right)
    numerator = sum((x-a)*(y-b) for x,y in zip(left,right))
    denominator = math.sqrt(sum((x-a)**2 for x in left)*sum((y-b)**2 for y in right))
    return numerator / denominator if denominator else None

def cohen_kappa(left: list[int], right: list[int]) -> float | None:
    if len(left) != len(right) or not left:
        raise ValueError("Des paires de notes sont nécessaires.")
    n = len(left)
    observed = sum(x == y for x,y in zip(left,right)) / n
    categories = set(left) | set(right)
    expected = sum(left.count(c)*right.count(c) for c in categories) / n**2
    return (observed-expected)/(1-expected) if expected < 1 else None

def agreement(human: list[dict[str, int]], ai: list[dict[str, int]]) -> dict:
    """Les listes doivent être alignées par conversation_id par l'appelant."""
    if len(human) != len(ai) or len(human) < 2:
        raise ValueError("Au moins deux conversations appariées sont nécessaires.")
    h = [compute_global_score(s) for s in human]
    a = [compute_global_score(s) for s in ai]
    return {"n": len(h), "pearson_global": pearson(h,a),
            "mae_global": sum(abs(x-y) for x,y in zip(h,a))/len(h),
            "kappa_by_criterion": {
                c.code: cohen_kappa([s[c.code] for s in human], [s[c.code] for s in ai])
                for c in RUBRIC}}
