package com.ztgx.nifi.processor;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.apache.nifi.annotation.behavior.SideEffectFree;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.dbcp.DBCPService;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.io.InputStreamCallback;
import org.apache.nifi.stream.io.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

@SideEffectFree
@Tags({"uniSwap v2", "数据保存"})
@CapabilityDescription("uniSwap v2 的数据保存至关系型数据库")
public class UpinsertUniSwapV2ToDB_bak extends AbstractProcessor {
    private final Set<Relationship> relationships;
    private final List<PropertyDescriptor> propDescriptors;
    public static final Relationship REL_SUCCESS = new Relationship.Builder()
            .description("保存成功。")
            .name("保存成功")
            .build();
    public static final Relationship REL_FAILURE = new Relationship.Builder()
            .name("异常")
            .description("当流文件因无法设置状态而失败时，它将在这里路由。 ")
            .build();
    static final PropertyDescriptor DEST_SERVICE = new PropertyDescriptor.Builder()
            .name("DEST_SERVICE")
            .description("目标数据源").required(true)
            .identifiesControllerService(DBCPService.class).build();
    public UpinsertUniSwapV2ToDB_bak() {
        final Set<Relationship> relationshipSet = new HashSet<>();
        relationshipSet.add(REL_SUCCESS);
        relationshipSet.add(REL_FAILURE);
        relationships = Collections.unmodifiableSet(relationshipSet);
        final List<PropertyDescriptor> pds = new ArrayList<>();
        pds.add(DEST_SERVICE);
        propDescriptors = Collections.unmodifiableList(pds);
    }

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return propDescriptors;
    }

    @Override
    public Set<Relationship> getRelationships() {
        return this.relationships;
    }
    @Override
    public void onTrigger(ProcessContext context, ProcessSession session) throws ProcessException {
        FlowFile flowFile = session.get();
        if (flowFile == null) {
            return;
        }
        final byte[] content = new byte[(int) flowFile.getSize()];
        session.read(flowFile, new InputStreamCallback() {
            @Override
            public void process(final InputStream in) throws IOException {
                StreamUtils.fillBuffer(in, content, true);
            }
        });


        final Charset charset = Charset.forName(StandardCharsets.UTF_8.name());
        String data = new String(content, charset);
        JSONObject jsonObject = JSON.parseObject(data);
        try {
            upInsertDB(jsonObject,context);
            session.transfer(flowFile, REL_SUCCESS);
        } catch (Exception e) {
            e.printStackTrace();
            session.transfer(flowFile, REL_FAILURE);
        }
    }

    private void upInsertDB(JSONObject jsonObject, ProcessContext context) {
        JSONObject data = jsonObject.getJSONObject("data");
        JSONArray transactions = data.getJSONArray("transactions");
        if (!transactions.isEmpty()){
            upInsertTranactions(transactions,context);
            upInsertSwaps(transactions,context);

        }
    }

    private void upInsertSwaps(JSONArray transactions, ProcessContext context) {

        Connection conn = null;
        PreparedStatement stmt = null;
        Statement stmts = null;
        ResultSet rs = null;
        final DBCPService dbcpService = context.getProperty(DEST_SERVICE).asControllerService(DBCPService.class);
        String sql ="INSERT INTO uniswap_v2_transaction_swap (id, amount0_in, amount0_out,amount1_in,amount1_out,amount_usd,swap_from,log_index,sender,swap_to,timestamp,transaction_id)\n" +
                "VALUES (?,?,?,?,?," +
                "?,?,?,?,?," +
                "?,?)\n" +
                "ON DUPLICATE KEY UPDATE\n" +
                "    amount_usd = ?,timestamp=?";
        conn = dbcpService.getConnection();
        try {
            stmt = conn.prepareStatement(sql);
            final int batchSize = 1000;
            int count = 0;
            for (int i = 0; i < transactions.size(); i++){
                JSONObject transaction = transactions.getJSONObject(i);
                String id = transaction.getString("id");
                String blockNumber = transaction.getString("blockNumber");
                String timestamp = transaction.getString("timestamp");
                JSONArray swaps = transaction.getJSONArray("swaps");
                if (!swaps.isEmpty()){
                    for (int j = 0; j < swaps.size(); j++){
                        JSONObject swap = swaps.getJSONObject(j);
                        String id1 = swap.getString("id");
                        String amount0In = swap.getString("amount0In");
                        String amount0Out = swap.getString("amount0Out");
                        String amount1In = swap.getString("amount1In");
                        String amount1Out = swap.getString("amount1Out");
                        String amountUSD = swap.getString("amountUSD");
                        String from = swap.getString("from");
                        String to = swap.getString("to");
                        String sender = swap.getString("sender");
                        String logIndex = swap.getString("logIndex");
                        Timestamp timestamp1 = swap.getTimestamp("timestamp");

                        stmt.setString(1, id1);
                        stmt.setString(2,amount0In);
                        stmt.setString(3,amount0Out);
                        stmt.setString(4,amount1In);
                        stmt.setString(5,amount1Out);
                        stmt.setString(6,amountUSD);
                        stmt.setString(7,from);
                        stmt.setString(8,to);
                        stmt.setString(9,sender);
                        stmt.setString(10,logIndex);
                        stmt.setTimestamp(11,timestamp1);
                        stmt.setString(12,id);
                        stmt.setString(13,amountUSD);
                        stmt.setTimestamp(14,timestamp1);

                        stmt.addBatch();
//                        if (++count % batchSize == 0) {
//                            stmt.executeBatch();
//                            stmt.clearBatch();
//                        }

                    }
                    stmt.executeBatch();
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        } finally {
            closeConn(conn, stmt, stmts, rs);
        }

    }

    private void upInsertTranactions(JSONArray transactions, ProcessContext context)  {
        Connection conn = null;
        PreparedStatement stmt = null;
        Statement stmts = null;
        ResultSet rs = null;
        final DBCPService dbcpService = context.getProperty(DEST_SERVICE).asControllerService(DBCPService.class);
        String sql = "INSERT INTO uniswap_v2_transaction (id, block_number, timestamp)\n" +
                "VALUES (?,?,?)\n" +
                "ON DUPLICATE KEY UPDATE\n" +
                "    block_number = ?,timestamp=?";
        conn = dbcpService.getConnection();
        try {
            stmt = conn.prepareStatement(sql);
            final int batchSize = 1000;
            int count = 0;
            for (int i = 0; i < transactions.size(); i++){
                JSONObject transaction = transactions.getJSONObject(i);
                String id = transaction.getString("id");
                String blockNumber = transaction.getString("blockNumber");

                Timestamp timestamp = transaction.getTimestamp("timestamp");
                stmt.setString(1, id);
                stmt.setString(2, blockNumber);
                stmt.setTimestamp(3, timestamp);
                stmt.setString(4,blockNumber);
                stmt.setTimestamp(5,timestamp);
                stmt.addBatch();
//                if (++count % batchSize == 0) {
//                    stmt.executeBatch();
//                    stmt.clearBatch();
//                }

            }
            stmt.executeBatch();
        } catch (SQLException e) {
            e.printStackTrace();
        } finally {
            closeConn(conn, stmt, stmts, rs);
        }

    }


    private void closeConn(Connection conn, PreparedStatement stmt, Statement stmts, ResultSet rs) {
        if (null != conn) {
            try {
                conn.close();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
        if (null != stmt) {
            try {
                stmt.close();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
        if (null != stmts) {
            try {
                stmts.close();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
        if (null != rs) {
            try {
                rs.close();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }
}
