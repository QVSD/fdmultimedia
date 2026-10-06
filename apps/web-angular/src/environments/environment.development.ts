export const environment = {
  production: false,
  // Same relative path as production: Nginx (or the `ng serve` proxy, see
  // proxy.conf.json) is responsible for routing this to the backend.
  apiBaseUrl: '/api',
  // Single release version; must equal the API and Worker versions (see docs/releases).
  version: '0.1.0-rc1',
};
