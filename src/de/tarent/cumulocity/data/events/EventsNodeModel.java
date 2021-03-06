package de.tarent.cumulocity.data.events;

import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.knime.core.data.DataCell;
import org.knime.core.data.DataColumnSpec;
import org.knime.core.data.DataColumnSpecCreator;
import org.knime.core.data.DataRow;
import org.knime.core.data.DataTableSpec;
import org.knime.core.data.DataType;
import org.knime.core.data.RowKey;
import org.knime.core.data.def.DefaultRow;
import org.knime.core.data.def.StringCell;
import org.knime.core.data.time.zoneddatetime.ZonedDateTimeCellFactory;
import org.knime.core.node.BufferedDataContainer;
import org.knime.core.node.BufferedDataTable;
import org.knime.core.node.CanceledExecutionException;
import org.knime.core.node.ExecutionContext;
import org.knime.core.node.NodeLogger;
import org.knime.core.node.port.PortObject;
import org.knime.core.node.port.PortType;

import com.telekom.m2m.cot.restsdk.event.Event;
import com.telekom.m2m.cot.restsdk.event.EventApi;
import com.telekom.m2m.cot.restsdk.util.CotSdkException;
import com.telekom.m2m.cot.restsdk.util.ExtensibleObject;
import com.telekom.m2m.cot.restsdk.util.Filter.FilterBuilder;

import de.tarent.cumulocity.connector.CumulocityPortObject;
import de.tarent.cumulocity.data.RetrieveDataNodeModel;

/**
 * implementation of the node model of the "Events" node.
 * 
 * retrieves events from Cumulocity
 * 
 * @author tarent solutions GmbH
 */
public class EventsNodeModel extends RetrieveDataNodeModel {

	private static final String ATTRIBUTE_NAME = "name";
	private static final NodeLogger logger = NodeLogger.getLogger(EventsNodeModel.class);

	/*
	 * we have 1 input port (connection info) and one output port with the events
	 */
	protected EventsNodeModel() {
		super(new PortType[] { CumulocityPortObject.TYPE });
	}

	/**
	 * {@inheritDoc}
	 * 
	 * @throws CanceledExecutionException
	 */
	@Override
	protected PortObject[] execute(final PortObject[] inData, final ExecutionContext exec)
			throws CanceledExecutionException {
		final EventApi eventApi = getEventApi((CumulocityPortObject) inData[0]);

		final Optional<FilterBuilder> optionalFilter = getOptionalDateFilter();
		final Iterator<Event> eventsIterator;
		if (optionalFilter.isPresent()) {
			eventsIterator = eventApi.getEvents(optionalFilter.get()).stream().iterator();
		} else {
			eventsIterator = eventApi.getEvents().stream().iterator();			
		}

		long rowIx = 0;
		final long maxNum;
		if (m_maxNumRecordsSettings.getLongValue() > 0) {
			maxNum = m_maxNumRecordsSettings.getLongValue();
		} else {
			maxNum = Long.MAX_VALUE;
		}
		final DataCell[] cells = new DataCell[7];
		final DataTableSpec outputSpec = outputTableSpec();
		final BufferedDataContainer container = exec.createDataContainer(outputSpec);
		try {
			while (eventsIterator.hasNext()) {

				final Event event = eventsIterator.next();
				final Map<String, Object> attributes = event.getAttributes();

				cells[0] = new StringCell(event.getId());
				cells[1] = new StringCell(event.getType());
				final Date creationtime = event.getCreationTime();
				cells[2] = ZonedDateTimeCellFactory.create(m_dateFormat.format(creationtime));
				final Object source = attributes.get("source");
				if (source != null && source instanceof ExtensibleObject) {
					final ExtensibleObject s = (ExtensibleObject) source;
					if (s.has(ATTRIBUTE_NAME)) {
						cells[3] = new StringCell(s.get(ATTRIBUTE_NAME).toString());
					} else {
						cells[3] = DataType.getMissingCell();
					}
					cells[4] = new StringCell(s.get("id").toString());
				}
				final Date time = (Date) attributes.get("time");
				if (time != null) {
					cells[5] = ZonedDateTimeCellFactory.create(m_dateFormat.format(time));
				} else {
					cells[5] = DataType.getMissingCell();
				}
				cells[6] = new StringCell(event.getText());

				final RowKey key = RowKey.createRowKey(rowIx);
				final DataRow row = new DefaultRow(key, cells);
				container.addRowToTable(row);
				rowIx++;
				if (rowIx >= maxNum) {
					logger.info("Retrieved maximal number (" + rowIx + ") of events to retrieve, will stop.");
					break;
				}
				exec.checkCanceled();
			}
		} catch (CotSdkException cse) {
			if (rowIx == 0) {
				logger.error("Failed to retrieve any events!");
				throw cse;
			} else {
				logger.error("Failed to retrieved only "+rowIx+" events, but there might be more!");
			}
			logger.error("Root cause: " + cse.getMessage());
		} finally {
			container.close();
			logger.info("Retrieved " + rowIx + " events.");
		}
		return new BufferedDataTable[] { container.getTable() };
	}

	protected DataTableSpec outputTableSpec() {
		final List<DataColumnSpec> columns = new ArrayList<>();
		columns.add(new DataColumnSpecCreator("Event ID", StringCell.TYPE).createSpec());
		columns.add(new DataColumnSpecCreator("Event Type", StringCell.TYPE).createSpec());
		columns.add(new DataColumnSpecCreator("Creation Time", ZonedDateTimeCellFactory.TYPE).createSpec());
		columns.add(new DataColumnSpecCreator("Source Name", StringCell.TYPE).createSpec());
		columns.add(new DataColumnSpecCreator("Source ID", StringCell.TYPE).createSpec());
		columns.add(new DataColumnSpecCreator("Time", ZonedDateTimeCellFactory.TYPE).createSpec());
		columns.add(new DataColumnSpecCreator("Description", StringCell.TYPE).createSpec());
		final DataTableSpec outputSpec = new DataTableSpec(columns.toArray(new DataColumnSpec[0]));
		return outputSpec;
	}
}
