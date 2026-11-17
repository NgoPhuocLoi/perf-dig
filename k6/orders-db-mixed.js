// Mixed load on the two JDBC endpoints, each at its own fixed arrival rate, ~2 minutes:
//   GET /orders/db                  40 req/s   cheap keyset page (index range scan, a few ms)
//   GET /orders/db/customer-totals   1 req/10s heavy 50M-row join + aggregate (~8.5 s alone)
//
// The point: see how a trickle of heavy queries affects the fast endpoint's latency
// while both share the same JDBC pool and Postgres.
//
//   docker run --rm --network host -v "$PWD/k6:/scripts:ro" \
//     grafana/k6:2.3.0 run /scripts/orders-db-mixed.js
//
// Override with -e BASE_URL=... -e LIST_RATE=... -e TOTALS_EVERY=... -e DURATION=...
import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const LIST_RATE = Number(__ENV.LIST_RATE || 40); // /orders/db requests per second
const TOTALS_EVERY = __ENV.TOTALS_EVERY || '10s'; // one /customer-totals request per this period
const DURATION = __ENV.DURATION || '2m';
const MAX_ORDER_ID = Number(__ENV.MAX_ORDER_ID || 50_000_000);

// One duration metric per endpoint so the summary shows each one's percentiles separately.
const listDuration = new Trend('orders_db_list_duration', true);
const totalsDuration = new Trend('orders_db_totals_duration', true);

export const options = {
  scenarios: {
    // ~40 x few ms => well under 1 request in flight; VUs only matter if the pool starves.
    orders_db_list: {
      executor: 'constant-arrival-rate',
      exec: 'list',
      rate: LIST_RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 10,
      maxVUs: 50,
    },
    // 1 per 10 s x ~8.5 s => about 1 in flight; a few spare VUs in case runs overlap and slow down.
    orders_db_totals: {
      executor: 'constant-arrival-rate',
      exec: 'totals',
      rate: 1,
      timeUnit: TOTALS_EVERY,
      duration: DURATION,
      preAllocatedVUs: 2,
      maxVUs: 5,
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  thresholds: {
    http_req_failed: ['rate<0.01'],
    dropped_iterations: ['count==0'],
  },
};

function hit(url, name, trend) {
  const res = http.get(url, { tags: { name }, timeout: '60s' });
  check(res, { [`${name} status is 200`]: (r) => r.status === 200 });
  trend.add(res.timings.duration);
}

export function list() {
  // Random starting id so requests spread over the table instead of re-reading page 1.
  const afterId = Math.floor(Math.random() * MAX_ORDER_ID);
  hit(`${BASE_URL}/orders/db?afterId=${afterId}`, 'GET /orders/db', listDuration);
}

export function totals() {
  hit(`${BASE_URL}/orders/db/customer-totals`, 'GET /orders/db/customer-totals', totalsDuration);
}
