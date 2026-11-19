const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_host_123";
const BOB_UID = "bob_listener_456";
const CHARLIE_UID = "charlie_outsider_789";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function validRoomPayload(roomCode, hostUid, hostUsername, nowDate = new Date(Date.now() - 2000)) {
  return {
    roomCode,
    hostUid,
    hostUsername,
    roomName: `${hostUsername}'s Jam`,
    active: true,
    memberUids: [hostUid],
    memberNames: { [hostUid]: hostUsername },
    trackId: "ytm_dQw4w9WgXcQ",
    trackTitle: "Never Gonna Give You Up",
    trackArtist: "Rick Astley",
    trackAlbum: "Whenever You Need Somebody",
    trackArtworkUrl: "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
    trackDurationMs: 213000,
    trackSource: "YOUTUBE_MUSIC",
    trackSourceId: "dQw4w9WgXcQ",
    trackStreamUrl: "",
    playing: true,
    positionMs: 12000,
    playbackUpdatedAtMs: Date.now() - 1000,
    updatedByUid: hostUid,
    updatedByUsername: hostUsername,
    createdAt: nowDate,
    updatedAt: nowDate,
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

test("Unauthenticated user: cannot read, list, or create listen rooms", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("listen_rooms").doc("KPL999").get());
  await assertFails(unauthDb.collection("listen_rooms").get());
  await assertFails(
    unauthDb.collection("listen_rooms").doc("KPL999").set(validRoomPayload("KPL999", ALICE_UID, "Alice"))
  );
});

test("Host: valid Kotlin room creation succeeds, shadow fields & invalid roomCode fail", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();

  await assertSucceeds(
    aliceDb.collection("listen_rooms").doc("KPL84X").set(validRoomPayload("KPL84X", ALICE_UID, "Alice"))
  );

  // Reject shadow ghost field
  await assertFails(
    aliceDb.collection("listen_rooms").doc("KPL85X").set({
      ...validRoomPayload("KPL85X", ALICE_UID, "Alice"),
      isAdmin: true,
    })
  );

  // Reject identity spoofing (hostUid != caller)
  await assertFails(
    aliceDb.collection("listen_rooms").doc("KPL86X").set(validRoomPayload("KPL86X", BOB_UID, "Bob"))
  );

  // Reject future tampered timestamp
  await assertFails(
    aliceDb.collection("listen_rooms").doc("KPL87X").set(
      validRoomPayload("KPL87X", ALICE_UID, "Alice", new Date(Date.now() + 120000))
    )
  );

  // Reject invalid lowercase room code
  await assertFails(
    aliceDb.collection("listen_rooms").doc("kpl88x").set(validRoomPayload("kpl88x", ALICE_UID, "Alice"))
  );
});

test("Join, Playback Sync, Leave & Close lifecycle with strict permissions", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const charlieDb = testEnv.authenticatedContext(CHARLIE_UID).firestore();

  const created = new Date(Date.now() - 10000);
  await assertSucceeds(
    aliceDb.collection("listen_rooms").doc("JAM123").set(validRoomPayload("JAM123", ALICE_UID, "Alice", created))
  );

  // Outsider Charlie cannot sync playback before joining
  await assertFails(
    charlieDb.collection("listen_rooms").doc("JAM123").update({
      playing: false,
      positionMs: 30000,
      playbackUpdatedAtMs: Date.now(),
      updatedByUid: CHARLIE_UID,
      updatedByUsername: "Charlie",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Bob looks up room by exact code and joins
  await assertSucceeds(bobDb.collection("listen_rooms").doc("JAM123").get());
  await assertSucceeds(
    bobDb.collection("listen_rooms").doc("JAM123").update({
      memberUids: [ALICE_UID, BOB_UID],
      memberNames: { [ALICE_UID]: "Alice", [BOB_UID]: "Bob" },
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Bob can now sync playback state
  await assertSucceeds(
    bobDb.collection("listen_rooms").doc("JAM123").update({
      trackId: "ytm_9bZkp7q19f0",
      trackTitle: "Gangnam Style",
      trackArtist: "PSY",
      trackAlbum: "PSY 6",
      trackArtworkUrl: "https://i.ytimg.com/vi/9bZkp7q19f0/hqdefault.jpg",
      trackDurationMs: 252000,
      trackSource: "YOUTUBE_MUSIC",
      trackSourceId: "9bZkp7q19f0",
      trackStreamUrl: "",
      playing: true,
      positionMs: 5000,
      playbackUpdatedAtMs: Date.now() - 500,
      updatedByUid: BOB_UID,
      updatedByUsername: "Bob",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Bob cannot close room (host only) or mutate createdAt
  await assertFails(
    bobDb.collection("listen_rooms").doc("JAM123").update({
      active: false,
      updatedAt: new Date(Date.now() - 500),
    })
  );
  await assertFails(
    aliceDb.collection("listen_rooms").doc("JAM123").update({
      createdAt: new Date(Date.now() - 500),
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Bob leaves room
  await assertSucceeds(
    bobDb.collection("listen_rooms").doc("JAM123").update({
      memberUids: [ALICE_UID],
      memberNames: { [ALICE_UID]: "Alice" },
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Host closes room -> terminal locked
  await assertSucceeds(
    aliceDb.collection("listen_rooms").doc("JAM123").update({
      active: false,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // No further updates allowed after terminal close
  await assertFails(
    aliceDb.collection("listen_rooms").doc("JAM123").update({
      playing: false,
      updatedByUid: ALICE_UID,
      updatedByUsername: "Alice",
      updatedAt: new Date(Date.now() - 200),
    })
  );
});

test("List query alignment: member-scoped query succeeds, unfiltered query fails", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const charlieDb = testEnv.authenticatedContext(CHARLIE_UID).firestore();

  await assertSucceeds(
    aliceDb.collection("listen_rooms").doc("SYNC77").set(validRoomPayload("SYNC77", ALICE_UID, "Alice"))
  );

  // Filtered Kotlin repository query succeeds
  await assertSucceeds(
    aliceDb.collection("listen_rooms").where("memberUids", "array-contains", ALICE_UID).get()
  );

  // Unfiltered collection scrape fails
  await assertFails(charlieDb.collection("listen_rooms").get());
});
