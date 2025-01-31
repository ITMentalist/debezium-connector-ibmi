package com.fnz.db2.journal.retrieve;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fnz.db2.journal.retrieve.RetrievalCriteria.JournalCode;
import com.fnz.db2.journal.retrieve.RetrievalCriteria.JournalEntryType;
import com.fnz.db2.journal.retrieve.exception.InvalidJournalFilterException;
import com.fnz.db2.journal.retrieve.exception.InvalidPositionException;
import com.fnz.db2.journal.retrieve.rjne0200.EntryHeader;
import com.fnz.db2.journal.retrieve.rjne0200.EntryHeaderDecoder;
import com.fnz.db2.journal.retrieve.rjne0200.FirstHeader;
import com.fnz.db2.journal.retrieve.rjne0200.FirstHeaderDecoder;
import com.fnz.db2.journal.retrieve.rjne0200.OffsetStatus;
import com.ibm.as400.access.AS400Message;
import com.ibm.as400.access.MessageFile;
import com.ibm.as400.access.ProgramParameter;
import com.ibm.as400.access.ServiceProgramCall;

/**
 * based on the work of Stanley Vong
 *
 */
public class RetrieveJournal {
	private static final Logger log = LoggerFactory.getLogger(RetrieveJournal.class);

	private static final JournalCode[] REQUIRED_JOURNAL_CODES = new JournalCode[] { JournalCode.D, JournalCode.R,
			JournalCode.C };
	private static final JournalEntryType[] REQURED_ENTRY_TYPES = new JournalEntryType[] { JournalEntryType.PT,
			JournalEntryType.PX, JournalEntryType.UP, JournalEntryType.UB, JournalEntryType.DL, JournalEntryType.DR,
			JournalEntryType.CT, JournalEntryType.CG, JournalEntryType.SC, JournalEntryType.CM };
	private static final FirstHeaderDecoder firstHeaderDecoder = new FirstHeaderDecoder();
	private static final EntryHeaderDecoder entryHeaderDecoder = new EntryHeaderDecoder();
	private static final SimpleDateFormat DATE_FORMATTER = new SimpleDateFormat("yyMMdd-hhmm");
	private final JournalReceivers journalRecievers;
	private final ParameterListBuilder builder = new ParameterListBuilder();

	RetrieveConfig config;
	private byte[] outputData = null;
	private FirstHeader header = null;
	private EntryHeader entryHeader = null;
	private int offset = -1;
	private JournalPosition position;
	private long totalTransferred = 0;

	public RetrieveJournal(RetrieveConfig config, JournalInfoRetrieval journalRetrieval) {
		this.config = config;
		journalRecievers = new JournalReceivers(journalRetrieval, config.maxServerSideEntries(), config.journalInfo());

		builder.withJournal(config.journalInfo().journalName, config.journalInfo().journalLibrary);
	}

	/**
	 * retrieves a block of journal data
	 *
	 * @param retrievePosition
	 * @return true if the journal was read successfully false if there was some
	 *         problem reading the journal
	 * @throws Exception
	 *
	 *                   CURAVLCHN - returns only available journals CURCHAIN will
	 *                   work though journals that have happened but may no longer
	 *                   be available if the journal is no longer available we need
	 *                   to capture this and log an error as we may have missed data
	 */
	public boolean retrieveJournal(JournalPosition retrievePosition) throws Exception {
		this.offset = -1;
		this.entryHeader = null;
		this.header = null;
		this.position = retrievePosition;

		log.debug("Fetch journal at postion {}", retrievePosition);
		final ServiceProgramCall spc = new ServiceProgramCall(config.as400().connection());
		spc.getServerJob().setLoggingLevel(0);
		builder.init();
		builder.withJournalEntryType(JournalEntryType.ALL);
		if (config.filtering() && !config.includeFiles().isEmpty()) {
			builder.withFileFilters(config.includeFiles());
		}

		final Optional<PositionRange> rangeOpt = journalRecievers.findRange(config.as400().connection(), retrievePosition);
		
		boolean hasData = rangeOpt.map( r -> { // this can only be used at the start
			builder.withStartingSequence(r.start().getOffset());
			/*
			 * Very important if *CURCHAIN or *CURVCHAIN is used then you can end up in a
			 * loop to overcome this the start journal must be set explicitly
			 */
			builder.withReceivers(r.start().getReciever(), r.start().getReceiverLibrary(), r.end().getReciever(),
					r.end().getReceiverLibrary());
			builder.withEnd(r.end().getOffset());

			if (retrievePosition.equals(r.end())) { // we are already at the end
				header = new FirstHeader(0, 0, 0, OffsetStatus.NO_MORE_DATA, Optional.of(r.end()));
				return false;
			}
			return true;
		}).orElseGet(() -> {
			builder.fromPositionToEnd(retrievePosition);
			return true;
		});

		if (!hasData) {
			return true;
		}

		final Optional<JournalPosition> latestJournalPosition = rangeOpt.map(x -> x.end());

		final ProgramParameter[] parameters = builder.build();
		spc.setProgram(JournalInfoRetrieval.JOURNAL_SERVICE_LIB, parameters);
		spc.setProcedureName("QjoRetrieveJournalEntries");
		spc.setAlignOn16Bytes(true);
		spc.setReturnValueFormat(ServiceProgramCall.RETURN_INTEGER);
		final boolean success = spc.run();
		if (success) {
			outputData = parameters[0].getOutputData();
			header = firstHeaderDecoder.decode(outputData);
			totalTransferred += header.totalBytes();
			log.debug("first header: {} ", header);
			offset = -1;
			if (header.status() == OffsetStatus.MORE_DATA_NEW_OFFSET && header.offset() == 0) {
				log.error("buffer too small skipping this entry {}", retrievePosition);
				header.nextPosition().ifPresent(retrievePosition::setPosition);
			}
			if (!hasData()) {
				log.debug("moving on to current position {}", latestJournalPosition);
				latestJournalPosition.ifPresent(l -> {
					header = header.withCurrentJournalPosition(l);
					retrievePosition.setPosition(l);
				});
			}
		} else {
			return reThrowIfFatal(retrievePosition, spc, latestJournalPosition, builder);
		}
		return success;
	}


	private boolean reThrowIfFatal(JournalPosition retrievePosition, final ServiceProgramCall spc,
			Optional<JournalPosition> latestJournalPosition, final ParameterListBuilder builder)
			throws InvalidPositionException, InvalidJournalFilterException, RetrieveJournalException {
		for (final AS400Message id : spc.getMessageList()) {
			final String idt = id.getID();
			if (idt == null) {
				log.error("Call failed position {} parameters {} no Id, message: {}", retrievePosition, builder, id.getText());
				continue;
			}
			switch (idt) {
			case "CPF7053": { // sequence number does not exist or break in receivers
				throw new InvalidPositionException(
						String.format("Call failed position %s parameters %s failed to find sequence or break in receivers: %s",
								retrievePosition, builder, getFullAS400MessageText(id)));
			}
			case "CPF9801": { // specify invalid receiver
				throw new InvalidPositionException(String.format("Call failed position %s parameters %s failed to find receiver: %s",
						retrievePosition, builder, getFullAS400MessageText(id)));
			}
			case "CPF7054": { // e.g. last < first
				throw new InvalidPositionException(
						String.format("Call failed position %s parameters %s failed to find offset or invalid offsets: %s",
								retrievePosition, builder, id.getText()));
			}
			case "CPF7060": { // object in filter doesn't exist, or was not journaled
				throw new InvalidJournalFilterException(
						String.format("Call failed position %s parameters %s object not found or not journaled: %s", retrievePosition, builder,
								getFullAS400MessageText(id)));
			}
			case "CPF7062": {
				log.debug("Call failed position {} parameters {} no data received, probably all filtered: {}", retrievePosition, builder, 
						id.getText());
				// if we're filtering we get no continuation offset just an error
				header = new FirstHeader(0, 0, 0, OffsetStatus.NO_MORE_DATA, latestJournalPosition);
				latestJournalPosition.ifPresent(l -> {
					header = header.withCurrentJournalPosition(l);
					retrievePosition.setPosition(l);
				});
				return true;
			}
			default:
				log.error("Call failed position {} parameters {} with error code {} message {}", retrievePosition, idt, 
						builder, getFullAS400MessageText(id));
			}
		}
		throw new RetrieveJournalException(String.format("Call failed position %s", retrievePosition));
	}

	private <T> String optToString(Optional<T> t) {
		return t.map(x -> x.toString()).orElse("<null>");
	}

	boolean shouldLimitRange() {
		return config.filtering();
	}

	private String getFullAS400MessageText(AS400Message message) {
		try {
			message.load(MessageFile.RETURN_FORMATTING_CHARACTERS);
			return String.format("%s %s", message.getText(), message.getHelp());
		} catch (final Exception e) {
			return message.getText();
		}
	}

	/**
	 * @return the current position or the next offset for fetching data when the
	 *         end of data is reached
	 */
	public JournalPosition getPosition() {
		return position;
	}

	public void setOutputData(byte[] b, FirstHeader header, JournalPosition position) {
		outputData = b;
		this.header = header;
		this.position = position;
	}

	public boolean futureDataAvailable() {
		return (header != null && header.hasFutureDataAvailable());
	}

	public String headerAsString() {
		final StringBuilder sb = new StringBuilder();
		if (header == null) {
			sb.append("null header\n");
		} else {
			sb.append(header);
		}
		return sb.toString();
	}

	// test without moving on
	public boolean hasData() {
		if (header.status() == OffsetStatus.NO_MORE_DATA) {
			return false;
		}
		if (offset < 0 && header.size() > 0) {
			return true;
		}
		if (offset > 0 && entryHeader.getNextEntryOffset() > 0) {
			return true;
		}
		return false;
	}

	public boolean nextEntry() {
		if (offset < 0) {
			if (header.size() > 0) {
				offset = header.offset();
				entryHeader = entryHeaderDecoder.decode(outputData, offset);
				if (alreadyProcessed(position, entryHeader)) {
					updatePosition(position, entryHeader);
					return nextEntry();
				}
				updatePosition(position, entryHeader);
				return true;
			} else {
				return false;
			}
		} else {
			final long nextOffset = entryHeader.getNextEntryOffset();
			if (nextOffset > 0) {
				offset += (int) nextOffset;
				entryHeader = entryHeaderDecoder.decode(outputData, offset);
				updatePosition(position, entryHeader);
				return true;
			}

			updateOffsetFromContinuation();
			return false;
		}
	}

	private void updateOffsetFromContinuation() {
		// after we hit the end use the continuation header for the next offset
		header.nextPosition().ifPresent(nextOffset -> {
			log.debug("Setting continuation offset {}", nextOffset);
			position.setPosition(nextOffset);
		});
	}

	private static boolean alreadyProcessed(JournalPosition position, EntryHeader entryHeader) {
		final JournalPosition entryPosition = new JournalPosition(position);
		updatePosition(entryPosition, entryHeader);
		return position.processed() && entryPosition.equals(position);

	}

	private static void updatePosition(JournalPosition p, EntryHeader entryHeader) {
		if (entryHeader.hasReceiver()) {
			p.setJournalReciever(entryHeader.getSequenceNumber(), entryHeader.getReceiver(),
					entryHeader.getReceiverLibrary(), true);
		} else {
			p.setOffset(entryHeader.getSequenceNumber(), true);
		}
	}

	public EntryHeader getEntryHeader() {
		return entryHeader;
	}

	public void dumpEntry() {
		final int start = offset + entryHeader.getEntrySpecificDataOffset();
		final long end = entryHeader.getNextEntryOffset();
		log.debug("total offset {} entry specific offset {} ", start, entryHeader.getEntrySpecificDataOffset());

		log.debug("next offset {}", end);
	}

	public int getOffset() {
		return offset;
	}

	public <T> T decode(JournalEntryDeocder<T> decoder) throws Exception {
//		Diagnostics.dump(outputData, start);
		try {
			final T t = decoder.decode(entryHeader, outputData, offset);
			return t;
		} catch (final Exception e) {
			dumpEntryToFile(config.dumpFolder());
			throw e;
		}
	}

	public void dumpEntryToFile(File path) {
		File dumpFile = null;
		if (path != null) {
			boolean created = false;
			for (int i = 0; !created && i < 100; i++) {

				final String formattedDate = DATE_FORMATTER.format(new Date());
				final File f = new File(path, String.format("%s-%s", formattedDate, Integer.toString(i)));
				try {
					created = f.createNewFile();
					if (created) {
						dumpFile = f;
					}
				} catch (final IOException e) {
					log.error("unable to dump to file", e);
				}
			}
			if (dumpFile != null) {
				try {
					final int start = offset;
					final int end = outputData.length;

					final byte[] bdata = Arrays.copyOfRange(outputData, start, end);
					Files.write(dumpFile.toPath(), bdata);

					final File entryInfo = new File(dumpFile.getPath() + ".txt");

					try (FileWriter fw = new FileWriter(entryInfo, true);
							BufferedWriter bw = new BufferedWriter(fw);
							PrintWriter out = new PrintWriter(bw)) {
						out.println(entryHeader.toString());
						out.print("dumped: ");
						out.println(end - start);
						out.print("total length: ");
						out.println(outputData.length);
					}
				} catch (final IOException e) {
					log.error("failed to dump problematic data", e);
				}
			} else {
				log.error("failed to create a dump file");
			}
		}
	}

	public FirstHeader getFirstHeader() {
		return header;
	}


	public long getTotalTransferred() {
		return totalTransferred;
	}

	public static class RetrieveJournalException extends Exception {
		private static final long serialVersionUID = 1L;

		public RetrieveJournalException(String message) {
			super(message);
		}
	}
	

}
