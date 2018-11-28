import sys

sys.path.insert(0, '.')

import os
import pprint
import random
import pathlib
import logging
import tempfile
import contextlib
import collections

import pytest
import docker as docker_py

from rnode_testing.util import log_box
from rnode_testing.profiling import log_prof_data
from rnode_testing.rnode import start_bootstrap

from typing import Iterator, List, TYPE_CHECKING
if TYPE_CHECKING:
    from _pytest.config.argparsing import Parser
    from _pytest.fixtures import FixtureRequest, SubRequest
    from docker.client import DockerClient
    from rnode_testing.rnode import Node

System = collections.namedtuple("System", ["config", "docker", "validators_data"])


TestConfig = collections.namedtuple("TestConfig", [
    "peer_count",
    "node_startup_timeout",
    "network_converge_timeout",
    "receive_timeout",
    "rnode_timeout",
    "blocks",
    "mount_dir",
])


KeyPair = collections.namedtuple("KeyPair", ["private_key", "public_key"])


ValidatorsData = collections.namedtuple("ValidatorsData", ["bonds_file", "bootstrap_keys", "peers_keys"])


def pytest_addoption(parser: "Parser") -> None:
    parser.addoption("--peer-count", action="store", default="2", help="number of peers in the network (excluding bootstrap node)")
    parser.addoption("--start-timeout", action="store", default="0", help="timeout in seconds for starting a node. Defaults to 30 + peer_count * 10")
    parser.addoption("--converge-timeout", action="store", default="0", help="timeout in seconds for network converge. Defaults to 200 + peer_count * 10")
    parser.addoption("--receive-timeout", action="store", default="0", help="timeout in seconds for receiving a message. Defaults to 10 + peer_count * 10")
    parser.addoption("--rnode-timeout", action="store", default="10", help="timeout in seconds for executing an rnode call (Examples: propose, show-logs etc.). Defaults to 10s")
    parser.addoption("--blocks", action="store", default="1", help="the number of deploys per test deploy")
    parser.addoption("--mount-dir", action="store", default=None, help="globally accesible directory for mounting between containers")


def make_timeout(peer_count: int, value: int, base: int, peer_factor: int = 10) -> int:
    return value if value > 0 else base + peer_count * peer_factor


def make_test_config(request: "FixtureRequest") -> TestConfig:
    peer_count = int(request.config.getoption("--peer-count"))
    start_timeout = int(request.config.getoption("--start-timeout"))
    converge_timeout = int(request.config.getoption("--converge-timeout"))
    receive_timeout = int(request.config.getoption("--receive-timeout"))
    rnode_timeout = int(request.config.getoption("--rnode-timeout"))
    blocks = int(request.config.getoption("--blocks"))
    mount_dir = request.config.getoption("--mount-dir")

    config = TestConfig(
        peer_count=peer_count,
        node_startup_timeout=180,
        network_converge_timeout=make_timeout(peer_count, converge_timeout, 200, 10),
        receive_timeout=make_timeout(peer_count, receive_timeout, 10, 10),
        rnode_timeout=rnode_timeout,
        blocks=blocks,
        mount_dir=mount_dir,
    )

    return config


@contextlib.contextmanager
def temporary_bonds_file(validator_keys: List[KeyPair]) -> Iterator[str]:
    (fd, file) = tempfile.mkstemp(prefix="rchain-bonds-file-", suffix=".txt", dir="/tmp")
    try:
        with os.fdopen(fd, "w") as f:
            for pair in validator_keys:
                bond = random.randint(1, 100)
                f.write("{} {}\n".format(pair.public_key, bond))
        yield file
    finally:
        os.unlink(file)


@contextlib.contextmanager
def validators_data(config: TestConfig) -> Iterator[ValidatorsData]:
    # Using pre-generated validator key pairs by rnode. We do this because warning below  with python generated keys
    # WARN  coop.rchain.casper.Validate$ - CASPER: Ignoring block 2cb8fcc56e... because block creator 3641880481... has 0 weight
    keys_file_path = os.path.join('resources/pregenerated-validator-private-public-key-pairs.txt')
    lines = pathlib.Path(keys_file_path).read_text().splitlines()
    validator_keys = [KeyPair(*line.split()) for line in lines[0:config.peer_count+1]]
    with temporary_bonds_file(validator_keys) as f:
        yield ValidatorsData(bonds_file=f, bootstrap_keys=validator_keys[0], peers_keys=validator_keys[1:])


@pytest.yield_fixture(scope='session')
def docker_client_session() -> Iterator["DockerClient"]:
    docker_client = docker_py.from_env()
    try:
        yield docker_client
    finally:
        docker_client.volumes.prune()
        docker_client.networks.prune()



@pytest.yield_fixture(scope="session")
def system(request: "SubRequest", docker_client_session: "DockerClient") -> Iterator[System]:
    cfg = make_test_config(request)
    with validators_data(cfg) as vd:
        try:
            yield System(cfg, docker_client_session, vd)
        finally:
            log_prof_data()


@pytest.yield_fixture(scope="module")
def bootstrap_node(system: System) -> Iterator["Node"]:
    with start_bootstrap(system.docker,
                         system.config.node_startup_timeout,
                         system.config.rnode_timeout,
                         system.validators_data,
                         mount_dir=system.config.mount_dir) as node:
        yield node
