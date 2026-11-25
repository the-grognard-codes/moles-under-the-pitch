package com.fumbbl.ffb.server.match;

import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/** Retains synthetic fixtures and exercises the production journal policy without a database. */
final class StateSyncFixtureCapture {
	static final int EARLY_REVISION = 50;
	private final Path directory;
	private final RecoveryJournal journal = new RecoveryJournal();
	private final JsonArray journalRows = new JsonArray();
	private final JsonArray moveRows = new JsonArray();
	private final List<RecoveryRepository.Record> entries = new ArrayList<>();
	private RecoveryArtifactJson.Encoded checkpoint;
	private RecoveryJournal.Cursor cursor;
	private long generation;
	private long checkpointGeneration;
	private int lastCompactionRevision;
	private RecoveryJournal.Cursor earlyCursor;
	private boolean earlyCaptured;
	private boolean firstCompaction = true;

	StateSyncFixtureCapture(Path directory) {
		this.directory = directory;
	}

	void start(SetupSession session) {
		if (generation != 0) throw new IllegalStateException("Fixture capture supports one match per JVM");
		generation = 1;
		long started = System.nanoTime();
		compact(session);
		journalRows.add(new JsonObject().add("generation", generation).add("revision", session.revision()).add("path", "initial-checkpoint")
			.add("checkpointBuildNanos", System.nanoTime() - started).add("checkpointBytes", checkpoint.envelopeBytes()));
	}

	void recordMove(JsonObject measurement) {
		moveRows.add(measurement);
	}

	void advance(SetupSession session, String matchId) throws IOException {
		if (session.isComplete()) {
			finish(session, matchId);
			return;
		}
		if (earlyCursor != null && writeNext("early-journal", session, earlyCursor)) earlyCursor = null;
		long start = System.nanoTime();
		RecoveryJournal.Change change = journal.change(session, cursor, generation);
		long journalBuildNanos = System.nanoTime() - start;
		if (change == null) return;
		JsonObject row = new JsonObject().add("generation", ++generation).add("revision", session.revision())
			.add("journalBuildNanos", journalBuildNanos);
		if (change.compact) {
			row.add("path", "compaction").add("precedingJournalEntries", cursor.entries)
				.add("precedingJournalBytes", cursor.journalBytes)
				.add("revisionsSinceCompaction", session.revision() - lastCompactionRevision);
			long checkpointStart = System.nanoTime();
			compact(session);
			row.add("checkpointBuildNanos", System.nanoTime() - checkpointStart).add("checkpointBytes", checkpoint.envelopeBytes());
			if (firstCompaction) {
				write("first-compaction", row.toString());
				firstCompaction = false;
			}
		} else {
			row.add("path", "journal").add("entryBytes", change.next.journalBytes - cursor.journalBytes);
			entries.add(new RecoveryRepository.Record(matchId, generation, change.json));
			cursor = change.next;
		}
		journalRows.add(row);
		if (!earlyCaptured && session.revision() == EARLY_REVISION) {
			String checkpointJson = session.recoveryArtifact();
			write("early", checkpointJson);
			earlyCursor = journal.cursor(new RecoveryRepository.Record(matchId, 1, checkpointJson), session);
			earlyCaptured = true;
		}
	}

	private void finish(SetupSession session, String matchId) throws IOException {
		write("move-timing", moveRows.toString());
		writeLate(session, matchId);
		write("terminal", session.recoveryArtifact());
		journalRows.add(new JsonObject().add("generation", generation + 1).add("revision", session.revision()).add("path", "terminal-checkpoint"));
		write("journal-cadence", journalRows.toString());
	}

	private void writeLate(SetupSession session, String matchId) throws IOException {
		RecoveryRepository.Record late = journal.restore(matchId, generation, checkpointGeneration, checkpoint.envelope(), entries);
		checkpoint = null;
		entries.clear();
		write("late", late.json);
		RecoveryJournal.Cursor lateCursor = journal.cursor(new RecoveryRepository.Record(matchId, 1, late.json), session);
		if (!writeNext("late-journal", session, lateCursor)) throw new IllegalStateException("Terminal decision has no journal delta");
	}

	private boolean writeNext(String label, SetupSession session, RecoveryJournal.Cursor previous) throws IOException {
		RecoveryJournal.Change next = journal.change(session, previous, 1);
		if (next == null) return false;
		if (next.compact) throw new IllegalStateException("Synthetic first journal entry exceeded limits");
		write(label, next.json);
		return true;
	}

	private void compact(SetupSession session) {
		JsonObject payload = session.recoveryCheckpointPayload();
		checkpoint = new RecoveryArtifactJson().encodeCanonicalCheckpoint(payload);
		cursor = journal.checkpointCursor(payload, checkpoint, session.recoveryJournalCounts(), session.recoveryNativeEpoch());
		checkpointGeneration = generation;
		lastCompactionRevision = session.revision();
		entries.clear();
	}

	private void write(String label, String value) throws IOException {
		Files.writeString(directory.resolve(label + ".json"), value, StandardOpenOption.CREATE_NEW);
	}
}
