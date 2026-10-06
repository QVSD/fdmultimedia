FROM node:22-alpine AS build
WORKDIR /build

COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npx ng build --configuration production

# nginx-unprivileged runs as a non-root user and listens on 8080.
FROM nginxinc/nginx-unprivileged:alpine

ARG APP_BUILD_COMMIT=unknown
ARG APP_BUILD_TIME=unknown
ARG APP_VERSION=0.1.0-rc1
LABEL org.opencontainers.image.title="fdmultimedia-web" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${APP_BUILD_COMMIT}" \
      org.opencontainers.image.created="${APP_BUILD_TIME}"

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist/web-angular/browser /usr/share/nginx/html

EXPOSE 8080
