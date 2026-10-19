"""Local, provider-independent safeguards for customer-facing decisions."""
import re
import unicodedata


def normalize(text: str) -> str:
    return ''.join(c for c in unicodedata.normalize('NFD', text)
                   if not unicodedata.combining(c)).lower().replace('’', "'")


def requests_human(message: str) -> bool:
    text = normalize(message)
    roles = r"(?:conseiller|conseillere|superviseur|humain|humaine|human|advisor|adviser)"
    if re.search(r"\b(?:ne veux pas|ne souhaite pas|pas besoin|don't want|do not want)\b[^.!?]*\b" + roles + r"\b", text, re.S):
        return False
    return bool(re.search(r"\b(?:je veux|je voudrais|je souhaite|j'aimerais|puis-je|i want|i would like|can i|parler|transferez|transferer|mettez-moi|mettez moi)\b.*\b" + roles + r"\b", text, re.S)
                or re.fullmatch(r"\s*(?:un |une )?(?:conseiller|conseillere|humain|humaine)(?: humain| humaine)?(?: s'il vous plait| svp)?[.!?\s]*", text))


def human_handover() -> dict:
    return dict(action="escalate", intent="OTHER", confidence=1.0,
                reply="Votre demande de conseiller humain est transmise. Je reste disponible pendant votre attente, jusqu’à la réponse d’un membre de l’équipe.",
                reason="Reprise humaine demandée par le client")


def reports_fraud(message: str) -> bool:
    text = normalize(message)
    return any(re.search(pattern, text, re.S) for pattern in (
        r"(?:je ne reconnais pas|je n'ai pas (?:autorise|effectue)|i (?:do not|don't) recogni[sz]e|i did not authori[sz]e).*(?:paiement|transaction|debit|payment|charge)",
        r"(?:paiement|transaction|debit|payment|charge).*(?:non (?:reconnu|autorise)|que je ne reconnais pas|unauthori[sz]ed|unrecogni[sz]ed)",
        r"(?:je (?:soupconne|suspecte)|i suspect).*fraud",
        r"(?:ma carte|mon compte|my card|my account).*(?:vole|pirate|compromis|stolen|hacked|compromised)",
    ))


def fraud_decision() -> dict:
    return dict(action="escalate", intent="FRAUD", confidence=1.0,
                reply="Votre signalement nécessite une prise en charge par un superviseur. "
                      "Aucun blocage de carte ni remboursement n'a été effectué par l'assistant.",
                reason="Suspicion de fraude : prise en charge humaine obligatoire")


def customer_reply(text: str) -> str:
    return re.sub(r"\b[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}\b",
                  "[référence interne masquée]", text, flags=re.I)


def claims_card_block(text: str) -> bool:
    return bool(re.search(
        r"(?:j'ai (?:bien )?bloque (?:votre|la) carte|(?:votre|la) carte a (?:bien )?ete bloquee|"
        r"i (?:have )?blocked (?:your|the) card|your card has been blocked)", normalize(text)))


def credit_application_query(message: str) -> bool:
    """How to start an application, never a request to grant or price a loan."""
    text = normalize(message)
    if re.search(r"\b(?:taux|mensualit\w*|renegoci\w*|rachat|rembours\w*|accord\w*|accepte\w*|eligib\w*|decaisse\w*)\b", text):
        return False
    return bool(re.search(r"\b(?:credit|pret)\b", text)
                and re.search(r"\bcomment\b.*\b(?:faire|demander|obtenir|preparer|constituer|deposer)\b", text))


def procedure_query(message: str) -> bool:
    text = normalize(message)
    return bool(credit_application_query(message)
                or re.search(r"\b(?:procedure|demarche|steps)\b", text)
                or re.search(r"\b(?:comment|how)\b.*\b(?:opposition|contester|bloquer|block|dispute|cloturer|fermer|ouvrir)\b", text)
                or re.search(r"\b(?:documents?|pieces|justificatifs?|delais?|conditions|modalites)\b.*\b(?:compte|carte|credit|clotur\w*|pret)\b", text))


def interface_or_greeting(message: str) -> bool:
    """Only social exchanges and navigation; customer and banking facts still use tools."""
    text = normalize(message).strip()
    if re.fullmatch(r"(?:bonjour|bonsoir|salut|merci(?: beaucoup)?|d'accord|au revoir|bonne journee)[\s!,.?]*", text):
        return True
    if re.fullmatch(r"(?:bonjour[\s!,.]*)?presentez? (?:votre role|vous)(?: en une phrase)?[\s!,.?]*", text):
        return True
    if re.search(r"\b(?:compte|carte|credit|virement|solde|iban|paiement|fraude|transaction|operation|montant|pin|password|code)\b", text):
        return False
    return bool(re.search(r"\b(?:ou|comment)\b", text)
                and re.search(r"\b(?:conversations|echanges|messages)\b", text)
                and re.search(r"\b(?:application|espace|interface|chat)\b", text))


def local_welcome_reply(message: str) -> str | None:
    """Routine welcome and application navigation do not need a model or banking data."""
    if not interface_or_greeting(message):
        return None
    text = normalize(message).strip().rstrip(" !,.?")
    if text.startswith('merci') and not any(word in text for word in ('conversations', 'echanges', 'messages')):
        return "Avec plaisir ! Avez-vous une autre question ?"
    if text in ('au revoir', 'bonne journee'):
        return "Bonne journée ! Vous pouvez retrouver cette discussion dans Messagerie."
    if text == "d'accord":
        return "Très bien. Comment puis-je vous aider pour la suite ?"
    if text in ('bonjour', 'bonsoir', 'salut'):
        return "Bonjour ! Je suis votre assistant IA. Comment puis-je vous aider aujourd’hui ?"
    if 'presente' in text:
        return "Je suis votre assistant IA bancaire : je vous aide à comprendre vos démarches et vos informations disponibles ; un conseiller peut prendre le relais."
    return "Ouvrez Messagerie, puis sélectionnez la discussion que vous souhaitez retrouver. Pour commencer un autre chat, choisissez Nouvelle demande dans cette page."


def grounded_procedure(documents: list[dict]) -> str | None:
    # Quote current published text. Neither a second model nor a high confidence
    # score can turn an unsupported instruction into a banking procedure.
    parts = []
    budget = 1500
    for document in documents[:2]:
        excerpt = str(document.get("excerpt") or "").strip()
        if not excerpt or not document.get("article_id") or document.get("score", 0) < .55:
            continue
        limit = min(650, budget)
        if len(excerpt) > limit:
            excerpt = excerpt[:limit]
            boundary = max(excerpt.rfind('. '), excerpt.rfind('.\n'))
            excerpt = excerpt[:boundary + 1] if boundary >= 0 else excerpt.rstrip() + "…"
        parts.append(f'« {excerpt} »')
        budget -= len(excerpt)
    if not parts:
        return None
    return "Voici les éléments de la procédure publiée :\n" + "\n".join(parts) + (
        "\nCes informations décrivent la procédure ; aucune opération n'a été exécutée par l'assistant.")
