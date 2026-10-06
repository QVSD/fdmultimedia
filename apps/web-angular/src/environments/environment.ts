export const environment = {
  production: true,
  // The browser only ever talks to Nginx; Nginx forwards /api/* to Spring
  // Boot. The frontend must never know the backend's internal host/port.
  apiBaseUrl: '/api',
  // Single release version; must equal the API and Worker versions (see docs/releases).
  version: '0.1.0-rc1',
};
