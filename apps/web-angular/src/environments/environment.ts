export const environment = {
  production: true,
  // The browser only ever talks to Nginx; Nginx forwards /api/* to Spring
  // Boot. The frontend must never know the backend's internal host/port.
  apiBaseUrl: '/api',
};
