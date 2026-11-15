// SPDX-License-Identifier: MIT
pragma solidity 0.8.38;

import {AccessControl} from "@openzeppelin/contracts/access/AccessControl.sol";

contract FreshSenseTraceability is AccessControl {
    bytes32 public constant EVIDENCE_WRITER_ROLE = keccak256("EVIDENCE_WRITER_ROLE");

    uint8 private constant LOT_REGISTERED = 1;
    uint8 private constant CRITICAL_TEMPERATURE = 2;
    uint32 private constant SCHEMA_V1 = 1;

    struct Evidence {
        bytes16 lotId;
        uint8 eventType;
        uint32 schemaVersion;
        bool exists;
        bytes32 digest;
    }

    mapping(bytes16 evidenceId => Evidence) private evidences;

    error InvalidRoleAccount();
    error InvalidEvidenceId();
    error InvalidLotId();
    error InvalidDigest();
    error UnsupportedEventType(uint8 eventType);
    error UnsupportedSchemaVersion(uint32 schemaVersion);
    error EvidenceAlreadyRecorded(bytes16 evidenceId);

    event EvidenceRecorded(
        bytes16 indexed evidenceId,
        bytes16 indexed lotId,
        bytes32 digest,
        uint8 eventType,
        uint32 schemaVersion
    );

    constructor(address admin, address initialWriter) {
        if (admin == address(0) || initialWriter == address(0) || admin == initialWriter) {
            revert InvalidRoleAccount();
        }
        _grantRole(DEFAULT_ADMIN_ROLE, admin);
        _grantRole(EVIDENCE_WRITER_ROLE, initialWriter);
    }

    /// @param digest SHA-256 de la evidencia canónica generada fuera de la cadena.
    function recordEvidence(
        bytes16 evidenceId,
        bytes16 lotId,
        bytes32 digest,
        uint8 eventType,
        uint32 schemaVersion
    ) external onlyRole(EVIDENCE_WRITER_ROLE) {
        if (evidenceId == bytes16(0)) revert InvalidEvidenceId();
        if (lotId == bytes16(0)) revert InvalidLotId();
        if (digest == bytes32(0)) revert InvalidDigest();
        if (eventType != LOT_REGISTERED && eventType != CRITICAL_TEMPERATURE) {
            revert UnsupportedEventType(eventType);
        }
        if (schemaVersion != SCHEMA_V1) revert UnsupportedSchemaVersion(schemaVersion);
        if (evidences[evidenceId].exists) revert EvidenceAlreadyRecorded(evidenceId);

        evidences[evidenceId] = Evidence(lotId, eventType, schemaVersion, true, digest);
        emit EvidenceRecorded(evidenceId, lotId, digest, eventType, schemaVersion);
    }

    function getEvidence(bytes16 evidenceId) external view returns (Evidence memory) {
        return evidences[evidenceId];
    }

    function verifyEvidence(bytes16 evidenceId, bytes32 digest)
        external
        view
        returns (bool exists, bool matches)
    {
        Evidence storage evidence = evidences[evidenceId];
        return (evidence.exists, evidence.exists && evidence.digest == digest);
    }
}
