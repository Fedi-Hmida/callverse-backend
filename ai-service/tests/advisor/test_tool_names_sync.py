"""
Garde-fou : verifie que les noms d'outils exposes au LLM (TOOL_SPECS)
correspondent exactement a ceux acceptes par le contrat (BankingTool),
et que les arguments requis par le schema LLM sont bien acceptes par
les methodes reelles de BackendClient.
Une desynchronisation (nom ou argument) fait planter l'appel reel
seulement au moment de l'invocation — ce test la detecte avant.
"""
from app.advisor.tools import TOOL_SPECS, TOOL_IMPLEMENTATIONS
from app.contracts.advisor import BankingTool


def test_tool_specs_noms_alignes_sur_le_contrat():
    contract_names = set(BankingTool.__args__)
    spec_names = {spec["name"] for spec in TOOL_SPECS}

    assert spec_names == contract_names, (
        f"Desynchronisation entre TOOL_SPECS et BankingTool.\n"
        f"Dans TOOL_SPECS mais pas dans le contrat: {spec_names - contract_names}\n"
        f"Dans le contrat mais pas dans TOOL_SPECS: {contract_names - spec_names}"
    )


def test_tool_implementations_couvrent_tous_les_outils_backend():
    # search_knowledge_base est volontairement absent de TOOL_IMPLEMENTATIONS
    # (gere via app/advisor/kb/retriever.py, pas via le backend)
    backend_tool_names = {s["name"] for s in TOOL_SPECS} - {"search_knowledge_base"}
    assert set(TOOL_IMPLEMENTATIONS.keys()) == backend_tool_names


def test_tool_specs_arguments_compatibles_avec_backend_client():
    """
    Pour chaque outil backend (hors search_knowledge_base), verifie que
    les proprietes requises du schema LLM correspondent aux parametres
    reellement acceptes par la methode BackendClient correspondante.
    """
    import inspect

    from app.clients.backend_client import BackendClient

    for spec in TOOL_SPECS:
        name = spec["name"]
        if name == "search_knowledge_base":
            continue

        method = getattr(BackendClient, name)
        sig_params = set(inspect.signature(method).parameters) - {"self"}
        required_props = set(spec["input_schema"].get("required", []))

        missing_in_method = required_props - sig_params
        assert not missing_in_method, (
            f"L'outil '{name}' demande au LLM les arguments {required_props}, "
            f"mais BackendClient.{name}() n'accepte que {sig_params}. "
            f"Manquants côté méthode : {missing_in_method}"
        )