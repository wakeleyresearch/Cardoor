const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function validProfile(uid, role = "curator") {
  const now = new Date();
  return {
    userId: uid,
    displayName: "Alice Archivist",
    email: "alice@foliograph.org",
    role,
    preferredTheme: "light",
    createdAt: now,
    updatedAt: now,
  };
}

function validDocument(uid) {
  const now = new Date();
  return {
    userId: uid,
    title: "Neuro-Symbolic Reading Systems",
    author: "A. Turing",
    sourceFormat: "PDF",
    ditaTopicType: "concept",
    sourceDigest: "sha256_0123456789abcdef0123456789abcdef",
    provenanceStatus: "CLOUD_VERIFIED",
    attestedModel: "gemini-3.8-flash",
    truthManifestHash: "manifest_abcdef0123456789",
    merkleRootHash: "merkle_abcdef0123456789",
    shaclScore: 0.98,
    dofScore: 0.14,
    bayesianFeedbackBlocked: true,
    sectionCount: 1,
    tripleCount: 1,
    summary: "A verified technical treatise.",
    createdAt: now,
    updatedAt: now,
  };
}

function validSection(docId) {
  const now = new Date();
  return {
    documentId: docId,
    orderIndex: 0,
    title: "Introduction",
    plainText: "Deterministic DITA primitives anchor generative synthesis.",
    ditaXml: "<concept id=\"sec_0\"><title>Introduction</title><conbody><p>Deterministic DITA primitives anchor generative synthesis.</p></conbody></concept>",
    ssmlProsody: "<speak><prosody rate=\"medium\">Deterministic DITA primitives anchor generative synthesis.</prosody></speak>",
    urn: "urn:foliograph:sha256:abcdef0123456789",
    sourceSpan: "page:1:0-64",
    locusChecksum: "locus_0123456789abcdef",
    provenanceChainHash: "chain_0123456789abcdef",
    dofScore: 0.12,
    locusDriftDistance: 0.08,
    reseedCount: 0,
    sidecarStatus: "CLOUD_LOCUS_LOCKED",
    convergenceType: "DIRECT_PROJECTION",
    createdAt: now,
    updatedAt: now,
  };
}

function validAuditLog(docId) {
  return {
    action: "PROVENANCE_COMMIT",
    documentId: docId,
    patchId: "patch-06",
    sourceDigest: "sha256_0123456789abcdef",
    inputStateHash: "in_0123456789abcdef",
    outputStateHash: "out_0123456789abcdef",
    truthManifestHash: "manifest_0123456789abcdef",
    dofScore: 0.12,
    bayesianFeedbackBlocked: true,
    latencyMs: 420,
    details: "Committed verified Markovian state.",
    createdAt: new Date(),
  };
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user: cannot read or list documents", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("documents").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
});

test("Cross-user isolation: Bob cannot read or write Alice's documents", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("users").doc(ALICE_UID).collection("documents").doc("doc_1").set(validDocument(ALICE_UID));
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("documents").doc("doc_1").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("documents").doc("doc_2").set(validDocument(ALICE_UID)));
});

test("Owner can create and read own profile, document, section, and audit log", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).set(validProfile(ALICE_UID, "curator")));
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("documents").doc("doc_1").set(validDocument(ALICE_UID)));
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("documents").doc("doc_1").collection("sections").doc("sec_0").set(validSection("doc_1")));
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("audit_logs").doc("log_1").set(validAuditLog("doc_1")));
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("documents").get());
});

test("RBAC & Security invariants: prevents role self-escalation, ghost fields, and Bayesian feedback override", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).set(validProfile(ALICE_UID, "reader")));

  // Attempt to self-promote role from reader to curator
  await assertFails(aliceDb.collection("users").doc(ALICE_UID).update({ role: "curator", updatedAt: new Date() }));

  // Attempt to inject ghost field on profile creation
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(bobDb.collection("users").doc(BOB_UID).set({ ...validProfile(BOB_UID, "curator"), isVerifiedAdmin: true }));

  // Attempt to create document with bayesianFeedbackBlocked = false
  await assertFails(aliceDb.collection("users").doc(ALICE_UID).collection("documents").doc("doc_bad").set({
    ...validDocument(ALICE_UID),
    bayesianFeedbackBlocked: false,
  }));
});

test("Audit logs are strictly append-only (update and delete rejected)", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const logRef = aliceDb.collection("users").doc(ALICE_UID).collection("audit_logs").doc("log_immutable");
  await assertSucceeds(logRef.set(validAuditLog("doc_1")));
  await assertFails(logRef.update({ details: "tampered" }));
  await assertFails(logRef.delete());
});

test("Orphaned section creation without parent document fails", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("documents").doc("missing_doc").collection("sections").doc("sec_0").set(validSection("missing_doc"))
  );
});
