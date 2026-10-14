from uuid import uuid4
from app.contracts.simulator import (
    SimulatorRequest,
    SimulatorMessage,
    SimulatorState,
    CustomerProfile,
)


def test_simulator_request_minimal():
    req = SimulatorRequest(
        conversation_id=uuid4(),
        state=SimulatorState(
            profile=CustomerProfile.churn,
            patience=80,
            satisfaction=50,
            objective="faire opposition sur la carte",
        ),
    )
    assert req.state.profile == CustomerProfile.churn


def test_simulator_message_minimal():
    msg = SimulatorMessage(
        content="Je ne reconnais pas cette transaction, il faut bloquer ma carte.",
        status="en_cours",
        state=SimulatorState(
            profile=CustomerProfile.churn,
            patience=70,
            satisfaction=40,
            objective="faire opposition sur la carte",
        ),
    )
    assert msg.status == "en_cours"
