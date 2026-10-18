"""Validate same-call informational assessment; prose cannot verify bank operations."""
import json
import re
import unicodedata
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, ValidationError


class SimulatorTurn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    content: str = Field(min_length=1, max_length=2000)
    objective_kind: Literal["informational", "operational", "unknown"]
    objective_met: bool = Field(strict=True)
    evidence_quote: str = ""


def _normalize(text: str) -> str:
    return "".join(c for c in unicodedata.normalize("NFKD", text.casefold())
                   if not unicodedata.combining(c))


_INFORMATIONAL_START = re.compile(
    r"^(?:comprendre\b|connaitre\b|savoir\b|se renseigner\b|"
    r"obtenir (?:des informations|le detail|des precisions)\b|"
    r"quels?\b|quelles?\b|pourquoi\b|comment\b|combien\b|quand\b)")
_OPERATION_REQUEST = re.compile(
    r"\b(?:bloquez|debloquez|remboursez|fermez|cloturez|effectuez|appliquez|annulez|ouvrez|faites)\b|"
    r"\b(?:et|puis|ensuite)\s+(?:faire|bloquer|debloquer|rembourser|fermer|cloturer|effectuer|appliquer|annuler|ouvrir|"
    r"obtenir (?:un|le|mon|ce) remboursement|demander un remboursement)\b")
_NO_ANSWER = re.compile(
    r"\b(?:ne (?:peux|peut|pouvons|sais|sait|savons|connais|connait|connaissons) pas|"
    r"ne suis pas en mesure|n['’]ai (?:pas|aucune)|ne constitue pas|"
    r"n['’]est pas (?:confirme|verifie|certain)|ne sont pas (?:confirmes|verifies|certains)|"
    r"pas (?:en mesure|encore|disponible)|je (?:vais|verrai|transmets|transmettrai)|"
    r"nous (?:allons|transmettons)|en cours de verification|a verifier|"
    r"contactez (?:votre|un)|demandez (?:a|au)|si vous|sous reserve)\b")
_STOPWORDS = set((
    "a au aux avec avoir ce ces cet cette comprendre connaitre comment de des du d "
    "en et etre faire il je l la le les leur lui ma mes mon ne nos notre ou par pas "
    "pour pourquoi quand que quel quelle quelles quels qui s sa savoir se ses son "
    "sur un une vos votre vous obtenir des informations detail precisions renseigner "
    "necessaire necessaires requis requise preparer pouvoir banque bancaire"
).split())
_SYNONYMS = {
    "cloturer":"cloture", "fermeture":"cloture", "fermer":"cloture",
    "piece":"document", "justificatif":"document",
    "opposition":"carte", "bloquer":"carte", "bloquee":"carte", "bloque":"carte",
    "remboursement":"rembours", "rembourser":"rembours", "rembourse":"rembours",
}


def _topics(text: str) -> set[str]:
    words = re.findall(r"[a-z]+", _normalize(text))
    words = [word[:-1] if len(word) > 3 and word.endswith("s") else word for word in words]
    return {_SYNONYMS.get(word, word) for word in words if len(word) > 2 and word not in _STOPWORDS}


def reply_addresses_objective(objective: str, reply: str) -> bool:
    """A relevance gate for perceived quality, never proof an operation succeeded."""
    topics = _topics(objective)
    return bool(topics and topics & _topics(reply))


def is_informational_objective(objective: str) -> bool:
    text = _normalize(objective).strip()
    # Ambiguous/unrecognized and compound requests remain unverified by default.
    return bool(_INFORMATIONAL_START.search(text) and not _OPERATION_REQUEST.search(text))


def parse_turn(raw: str) -> SimulatorTurn | None:
    try:
        return SimulatorTurn.model_validate(json.loads(raw))
    except (json.JSONDecodeError, ValidationError, TypeError):
        # Unstructured wording may still be displayed, but never proves completion.
        return None


def informational_answer_verified(objective: str, messages: list[dict], turn: SimulatorTurn) -> bool:
    if (not turn.objective_met or turn.objective_kind != "informational"
            or not is_informational_objective(objective)):
        return False
    if not messages or messages[-1]["role"] != "advisor":
        return False
    if not any(message["role"] == "customer" for message in messages[:-1]):
        return False
    quote = " ".join(turn.evidence_quote.split())
    latest_reply = " ".join(messages[-1]["content"].split())
    # Evidence must come from the latest answer, not a prior turn or the client.
    if len(quote) < 30 or quote not in latest_reply or quote.endswith("?"):
        return False
    quote_end = latest_reply.find(quote) + len(quote)
    if latest_reply[quote_end:].lstrip().startswith("?"):
        return False
    if _NO_ANSWER.search(_normalize(latest_reply)):
        return False
    objective_topics = _topics(objective)
    quote_topics = _topics(quote)
    required_overlap = min(2, len(objective_topics))
    return bool(required_overlap and len(objective_topics & quote_topics) >= required_overlap
                and len(quote_topics - objective_topics) >= 3)
