package org.embulk.output.kintone;

import com.kintone.client.KintoneClient;
import com.kintone.client.KintoneClientBuilder;
import com.kintone.client.model.record.Record;
import com.kintone.client.model.record.RecordForUpdate;
import com.kintone.client.model.record.UpdateKey;
import org.embulk.config.TaskReport;
import org.embulk.spi.Column;
import org.embulk.spi.Exec;
import org.embulk.spi.Page;
import org.embulk.spi.PageReader;
import org.embulk.spi.Schema;
import org.embulk.spi.TransactionalPageOutput;

import java.util.ArrayList;
import java.util.HashMap;

public class KintonePageOutput
        implements TransactionalPageOutput
{
    private PageReader pageReader;
    private PluginTask task;
    private KintoneClient client;

    public KintonePageOutput(PluginTask task, Schema schema)
    {
        this.pageReader = new PageReader(schema);
        this.task = task;
    }

    @Override
    public void add(Page page)
    {
        switch (task.getMode()) {
            case INSERT:
                insertPage(page);
                break;
            case UPDATE:
                updatePage(page);
                break;
            case UPSERT:
                // TODO upsertPage
            default:
                throw new UnsupportedOperationException(
                        "kintone output plugin does not support upsert");
        }
    }

    @Override
    public void finish()
    {
        // noop
    }

    @Override
    public void close()
    {
        if (this.client == null) {
            return;
        }
        try {
            this.client.close();
        }
        catch (Exception e) {
            throw new RuntimeException("kintone throw exception", e);
        }
    }

    @Override
    public void abort()
    {
        // noop
    }

    @Override
    public TaskReport commit()
    {
        return Exec.newTaskReport();
    }

    public interface Consumer<T>
    {
        public void accept(T t);
    }

    public void connect(final PluginTask task)
    {
        KintoneClientBuilder builder = KintoneClientBuilder.create("https://" + task.getDomain());
        if (task.getGuestSpaceId().isPresent()) {
            builder.setGuestSpaceId(task.getGuestSpaceId().or(-1));
        }
        if (task.getBasicAuthUsername().isPresent() && task.getBasicAuthPassword().isPresent()) {
            builder.withBasicAuth(task.getBasicAuthUsername().get(),
                    task.getBasicAuthPassword().get());
        }

        if (task.getUsername().isPresent() && task.getPassword().isPresent()) {
            this.client = builder
                .authByPassword(task.getUsername().get(), task.getPassword().get())
                .build();
        }
        else if (task.getToken().isPresent()) {
            this.client = builder
                .authByApiToken(task.getToken().get())
                .build();
        }
    }

    private void execute(Consumer<KintoneClient> operation)
    {
        connect(task);
        operation.accept(this.client);
    }

    private void insertPage(final Page page)
    {
        execute(client -> {
            try {
                ArrayList<Record> records = new ArrayList<>();
                pageReader.setPage(page);
                KintoneColumnVisitor visitor = new KintoneColumnVisitor(pageReader,
                        task.getColumnOptions());
                while (pageReader.nextRecord()) {
                    Record record = new Record();
                    visitor.setRecord(record);
                    for (Column column : pageReader.getSchema().getColumns()) {
                        column.visit(visitor);
                    }

                    records.add(record);
                    if (records.size() == 100) {
                        client.record().addRecords(task.getAppId(), records);
                        records.clear();
                    }
                }
                if (records.size() > 0) {
                    client.record().addRecords(task.getAppId(), records);
                }
            }
            catch (Exception e) {
                throw new RuntimeException("kintone throw exception", e);
            }
        });
    }

    private void updatePage(final Page page)
    {
        execute(client -> {
            try {
                ArrayList<RecordForUpdate> updateRecords = new ArrayList<RecordForUpdate>();
                pageReader.setPage(page);
                KintoneColumnVisitor visitor = new KintoneColumnVisitor(pageReader,
                        task.getColumnOptions());
                while (pageReader.nextRecord()) {
                    Record record = new Record();
                    UpdateKey updateKey = new UpdateKey();
                    visitor.setRecord(record);
                    visitor.setUpdateKey(updateKey);
                    for (Column column : pageReader.getSchema().getColumns()) {
                        column.visit(visitor);
                    }

                    updateRecords.add(new RecordForUpdate(updateKey, record));
                    if (updateRecords.size() == 100) {
                        client.record().updateRecords(task.getAppId(), updateRecords);
                        updateRecords.clear();
                    }
                }
                if (updateRecords.size() > 0) {
                    client.record().updateRecords(task.getAppId(), updateRecords);
                }
            }
            catch (Exception e) {
                throw new RuntimeException("kintone throw exception", e);
            }
        });
    }
}
