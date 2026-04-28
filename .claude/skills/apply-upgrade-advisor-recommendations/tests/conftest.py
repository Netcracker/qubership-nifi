import sys
from pathlib import Path
import pytest

sys.path.insert(0, str(Path(__file__).parent.parent / "scripts"))


@pytest.fixture
def minimal_pg():
    return {
        "identifier": "pg-root",
        "name": "RootPG",
        "variables": {},
        "processors": [],
        "controllerServices": [],
        "processGroups": [],
        "connections": [],
    }
