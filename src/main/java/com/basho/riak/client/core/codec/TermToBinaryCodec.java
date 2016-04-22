package com.basho.riak.client.core.codec;

import com.basho.riak.client.api.RiakException;
import com.basho.riak.client.core.query.timeseries.Cell;
import com.basho.riak.client.core.query.timeseries.QueryResult;
import com.basho.riak.client.core.query.timeseries.Row;
import com.basho.riak.protobuf.RiakTsPB;
import com.ericsson.otp.erlang.*;
import com.google.protobuf.ByteString;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

public class TermToBinaryCodec
{
    private static final class Messages
    {
        public static final OtpErlangAtom tsGetReq = new OtpErlangAtom("tsgetreq");
        public static final OtpErlangAtom tsGetResp = new OtpErlangAtom("tsgetresp");

        public static final OtpErlangAtom tsQueryReq = new OtpErlangAtom("tsqueryreq");
        public static final OtpErlangAtom tsQueryResp = new OtpErlangAtom("tsqueryresp");
        public static final OtpErlangAtom tsInterpolation = new OtpErlangAtom("tsinterpolation");

        public static final OtpErlangAtom tsPutReq = new OtpErlangAtom("tsputreq");
        public static final OtpErlangAtom tsPutResp = new OtpErlangAtom("tsputresp");

        public static final OtpErlangAtom tsDelReq = new OtpErlangAtom("tsdelreq");
        public static final OtpErlangAtom tsDelResp = new OtpErlangAtom("tsdelresp");

        public static final OtpErlangAtom rpbErrorResp = new OtpErlangAtom("rpberrorresp");
    }

    private static final OtpErlangAtom undefined = new OtpErlangAtom("undefined");

    public static OtpOutputStream encodeTsGetRequest(String tableName, Collection<Cell> keyValues, int timeout)
    {
        final OtpOutputStream os = new OtpOutputStream();
        os.write(OtpExternal.versionTag); // NB: this is the reqired 0x83 (131) value

        // NB: TsGetReq is a 4-tuple: tsgetreq, tableName, [key values], timeout
        os.write_tuple_head(4);
        os.write_any(Messages.tsGetReq);
        os.write_binary(tableName.getBytes(StandardCharsets.UTF_8));

        os.write_list_head(keyValues.size());
        for (Cell k : keyValues)
        {
            os.write_any(k.getErlangObject());
        }
        os.write_nil(); // NB: finishes the list

        os.write_long(timeout);

        return os;
    }

    public static QueryResult decodeTsGetResponse(byte[] response) throws OtpErlangDecodeException, InvalidTermToBinaryException
    {
        return decodeTsResponse(response);
    }

    public static OtpOutputStream encodeTsQueryRequest(String queryText)
    {
        final OtpOutputStream os = new OtpOutputStream();
        os.write(OtpExternal.versionTag); // NB: this is the reqired 0x83 (131) value

        // TsQueryReq is a 4-tuple: {'tsqueryreq', TsInt, boolIsStreaming, bytesCoverContext}
        os.write_tuple_head(4);
        os.write_any(Messages.tsQueryReq);

        // TsInterpolation is a 3-tuple
        // {'tsinterpolation', query, []} empty list is interpolations
        os.write_tuple_head(3);
        os.write_any(Messages.tsInterpolation);
        os.write_binary(queryText.getBytes(StandardCharsets.UTF_8));
        // interpolations is an empty list
        os.write_nil();

        // streaming is false for now
        os.write_boolean(false);

        // cover_context is an undefined atom
        os.write_any(undefined);

        return os;
    }

    public static QueryResult decodeTsQueryResponse(byte[] response) throws OtpErlangDecodeException, InvalidTermToBinaryException
    {
        return decodeTsResponse(response);
    }

    public static OtpOutputStream encodeTsPutRequest(String tableName, Collection<Row> rows)
    {
        final OtpOutputStream os = new OtpOutputStream();
        os.write(OtpExternal.versionTag); // NB: this is the reqired 0x83 (131) value

        // TsPutReq is a 4-tuple: {'tsputreq', tableName, [], [rows]}
        // columns is empte
        os.write_tuple_head(4);
        os.write_any(Messages.tsPutReq);
        os.write_binary(tableName.getBytes(StandardCharsets.UTF_8));
        // columns is an empty list
        os.write_nil();

        // write a list of rows
        // each row is a tuple of cells
        os.write_list_head(rows.size());
        for (Row r : rows)
        {
            os.write_tuple_head(r.getCellsCount());
            for (Cell c : r)
            {
                if (c == null)
                {
                    // NB: Null cells are represented as empty lists
                    os.write_nil();
                }
                else
                {
                    os.write_any(c.getErlangObject());
                }
            }
        }
        os.write_nil();

        return os;
    }

    private static QueryResult decodeTsResponse(byte[] response) throws OtpErlangDecodeException, InvalidTermToBinaryException
    {
        QueryResult result = null;

        OtpInputStream is = new OtpInputStream(response);
        final int msgArity = is.read_tuple_head();
        // Response is:
        // {'rpberrorresp', ErrMsg, ErrCode}
        // {'tsgetresp', {ColNames, ColTypes, Rows}}
        // {'tsqueryresp', {ColNames, ColTypes, Rows}}
        final String respAtom = is.read_atom();
        switch (respAtom)
        {
            case "rpberrorresp":
                // TODO process error
                assert (msgArity == 3);
                break;
            case "tsgetresp":
            case "tsqueryresp":
                assert (msgArity == 2);

                final int dataArity = is.read_tuple_head();
                assert (dataArity == 3);

                final ArrayList<RiakTsPB.TsColumnDescription> columnDescriptions = parseColumnDescriptions(is);

                final ArrayList<RiakTsPB.TsRow> rows = parseRows(is, columnDescriptions);

                result = new QueryResult(columnDescriptions, rows);

                break;
            default:
                // TODO GH-611 throw exception?
        }

        return result;
    }

    private static ArrayList<RiakTsPB.TsColumnDescription> parseColumnDescriptions(OtpInputStream is)
            throws OtpErlangDecodeException
    {
        final int colNameCount = is.read_list_head();
        final String[] columnNames = new String[colNameCount];
        for (int colNameIdx = 0; colNameIdx < colNameCount; colNameIdx++)
        {
            final String colName = new String(is.read_binary(), StandardCharsets.UTF_8);
            columnNames[colNameIdx] = colName;

            final boolean isLastRow = colNameIdx + 1 == colNameCount;
            if (isLastRow)
            {
                is.read_nil();
            }
        }


        final int colTypeCount = is.read_list_head();
        assert (colNameCount == colTypeCount);
        final String[] columnTypes = new String[colTypeCount];

        for (int colTypeIdx = 0; colTypeIdx < colTypeCount; colTypeIdx++)
        {
            final String colType = is.read_atom();
            columnTypes[colTypeIdx] = colType;

            final boolean isLastRow = colTypeIdx + 1 == colNameCount;
            if (isLastRow)
            {
                is.read_nil();
            }
        }

        final ArrayList<RiakTsPB.TsColumnDescription> columnDescriptions = new ArrayList<>(colNameCount);
        for (int colDescIdx = 0; colDescIdx < colNameCount; colDescIdx++)
        {

            final RiakTsPB.TsColumnDescription desc = RiakTsPB.TsColumnDescription.newBuilder().setName(
                    ByteString.copyFromUtf8(columnNames[colDescIdx])).setType(RiakTsPB.TsColumnType.valueOf(
                    columnTypes[colDescIdx].toUpperCase(Locale.US))).build();
            columnDescriptions.add(desc);
        }
        return columnDescriptions;
    }

    private static ArrayList<RiakTsPB.TsRow> parseRows(OtpInputStream is, List<RiakTsPB.TsColumnDescription> columnDescriptions)
            throws OtpErlangDecodeException, InvalidTermToBinaryException
    {
        final int rowCount = is.read_list_head();
        final ArrayList<RiakTsPB.TsRow> rows = new ArrayList<>(rowCount);

        for (int rowIdx = 0; rowIdx < rowCount; rowIdx++)
        {
            final boolean isLastRow = rowIdx + 1 == columnDescriptions.size();
            rows.add(parseRow(is, columnDescriptions, isLastRow));
        }
        return rows;
    }

    private static RiakTsPB.TsRow parseRow(OtpInputStream is, List<RiakTsPB.TsColumnDescription> columnDescriptions, Boolean isLastRow)
            throws OtpErlangDecodeException, InvalidTermToBinaryException
    {
        final int rowDataCount = is.read_tuple_head();
        assert (columnDescriptions.size() == rowDataCount);

        final Cell[] cells = new Cell[rowDataCount];
        for (int j = 0; j < rowDataCount; j++)
        {
            final OtpErlangObject cell = is.read_any();
            cells[j] = parseCell(columnDescriptions, j, cell);
        }

        if (isLastRow)
        {
            is.read_nil();
        }

        return new Row(cells).getPbRow();
    }

    private static Cell parseCell(List<RiakTsPB.TsColumnDescription> columnDescriptions, int j, OtpErlangObject cell) throws InvalidTermToBinaryException
    {
        if (cell instanceof OtpErlangBinary)
        {
            OtpErlangBinary v = (OtpErlangBinary) cell;
            String s = new String(v.binaryValue(), StandardCharsets.UTF_8);
            return new Cell(s);
        }
        else if (cell instanceof OtpErlangLong)
        {
            OtpErlangLong v = (OtpErlangLong) cell;
            if (columnDescriptions.get(j).getType() == RiakTsPB.TsColumnType.TIMESTAMP)
            {
                return Cell.newTimestamp(v.longValue());
            }
            else
            {
                return new Cell(v.longValue());
            }
        }
        else if (cell instanceof OtpErlangDouble)
        {
            OtpErlangDouble v = (OtpErlangDouble) cell;
            return new Cell(v.doubleValue());
        }
        else if (cell instanceof OtpErlangAtom)
        {
            OtpErlangAtom v = (OtpErlangAtom) cell;
            return new Cell(v.booleanValue());
        }
        else if (cell instanceof OtpErlangList)
        {
            final OtpErlangList l = (OtpErlangList) cell;
            assert (l.arity() == 0);
            return null;
        }
        else
        {
            throw new InvalidTermToBinaryException("Unknown cell type encountered: " + cell.toString() + ", unable to continue parsing.");
        }
    }
}
