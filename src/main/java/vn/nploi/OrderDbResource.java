package vn.nploi;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * Reads the orders table with plain JDBC. The table is large (tens of millions of rows),
 * so every query goes through an index and returns a bounded number of rows.
 * Methods return plain types, so Quarkus REST runs them on worker threads and blocking JDBC is fine.
 */
@Path("/orders/db")
@Produces(MediaType.APPLICATION_JSON)
public class OrderDbResource {

    private static final String COLUMNS = "id, customer_id, created_at, status, total_cents";
    private static final int MAX_LIMIT = 100;

    @Inject
    DataSource ds;

    /** Primary-key lookup (orders_pkey). */
    @GET
    @Path("/{id}")
    public Order byId(@PathParam("id") long id) throws SQLException {
        try (Connection conn = ds.getConnection();
                PreparedStatement ps = conn.prepareStatement("select " + COLUMNS + " from orders where id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new NotFoundException("order " + id + " not found");
                }
                return map(rs);
            }
        }
    }

    /** One page of orders plus the cursor for the next page (null on the last page). */
    public record Page(List<Order> items, Long nextAfterId) {
    }

    /**
     * Orders by id, paginated with a cursor (keyset): pass the previous page's nextAfterId as afterId.
     * Uses "where id > ? order by id limit ?" instead of OFFSET, so every page costs the same;
     * OFFSET would read and discard all earlier rows (deep pages take seconds on this table).
     * Optional customerId filters via the orders_customer index.
     */
    @GET
    public Page list(@QueryParam("afterId") @DefaultValue("0") long afterId,
            @QueryParam("limit") @DefaultValue("20") int limit,
            @QueryParam("customerId") Long customerId) throws SQLException {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new BadRequestException("limit must be between 1 and " + MAX_LIMIT);
        }
        String sql = "select " + COLUMNS + " from orders where id > ?"
                + (customerId != null ? " and customer_id = ?" : "")
                + " order by id limit ?";
        try (Connection conn = ds.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            ps.setLong(i++, afterId);
            if (customerId != null) {
                ps.setLong(i++, customerId);
            }
            // Fetch one extra row to know whether another page exists
            ps.setInt(i, limit + 1);
            List<Order> orders = new ArrayList<>(limit + 1);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    orders.add(map(rs));
                }
            }
            if (orders.size() <= limit) {
                return new Page(orders, null);
            }
            List<Order> items = orders.subList(0, limit);
            return new Page(items, items.get(limit - 1).id());
        }
    }

    /** One page of orders with page-number metadata. */
    public record OffsetPage(List<Order> items, int page, int size, long totalCount, long totalPages) {
    }

    /**
     * Orders by id, paginated with OFFSET and a total count. Deliberately the expensive way, to compare
     * with the cursor endpoint and to make Postgres the bottleneck under load:
     * - "select count(*)" scans the whole table on every request (~1 s for 50M rows).
     * - "offset (page-1)*size" reads and discards every earlier row, so deep pages take seconds.
     * Both queries share one connection, so a slow request holds a pool connection for their total time.
     * Optional customerId narrows both queries to the orders_customer index (fast).
     */
    @GET
    @Path("/offset")
    public OffsetPage listByOffset(@QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("customerId") Long customerId) throws SQLException {
        if (page < 1) {
            throw new BadRequestException("page must be 1 or greater");
        }
        if (size < 1 || size > MAX_LIMIT) {
            throw new BadRequestException("size must be between 1 and " + MAX_LIMIT);
        }
        String where = customerId != null ? " where customer_id = ?" : "";
        long offset = (long) (page - 1) * size;
        try (Connection conn = ds.getConnection()) {
            long totalCount;
            try (PreparedStatement ps = conn.prepareStatement("select count(*) from orders" + where)) {
                if (customerId != null) {
                    ps.setLong(1, customerId);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    totalCount = rs.getLong(1);
                }
            }

            List<Order> items = new ArrayList<>(size);
            try (PreparedStatement ps = conn.prepareStatement(
                    "select " + COLUMNS + " from orders" + where + " order by id limit ? offset ?")) {
                int i = 1;
                if (customerId != null) {
                    ps.setLong(i++, customerId);
                }
                ps.setInt(i++, size);
                ps.setLong(i, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        items.add(map(rs));
                    }
                }
            }

            long totalPages = (totalCount + size - 1) / size;
            return new OffsetPage(items, page, size, totalCount, totalPages);
        }
    }

    /** One customer with the sum of all their orders. */
    public record CustomerTotal(String name, long totalCents) {
    }

    /**
     * Order totals for every customer (50k rows). Still reads all 50M orders, but aggregates them by
     * customer_id first (in a CTE) and only then joins the 50k sums to customers, instead of joining
     * every order row to customers before grouping. On pglab it takes ~3.2 s (the old join-then-group
     * query took ~7 s). The query is kept exactly as written,
     * with no ORDER BY, so row order is whatever the plan produces.
     */
    @GET
    @Path("/customer-totals")
    public List<CustomerTotal> customerTotals() throws SQLException {
        try (Connection conn = ds.getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "WITH sum_totals AS (SELECT customer_id, SUM(total_cents) as sum FROM orders GROUP BY customer_id)"
                                + " SELECT c.name, st.sum FROM customers c JOIN sum_totals st ON c.id = st.customer_id");
                ResultSet rs = ps.executeQuery()) {
            List<CustomerTotal> totals = new ArrayList<>(50_000);
            while (rs.next()) {
                totals.add(new CustomerTotal(rs.getString(1), rs.getLong(2)));
            }
            return totals;
        }
    }

    private static Order map(ResultSet rs) throws SQLException {
        return new Order(
                rs.getLong("id"),
                rs.getLong("customer_id"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getString("status"),
                rs.getLong("total_cents"));
    }
}
