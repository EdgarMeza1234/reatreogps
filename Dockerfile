# RastreoGPS - Node 22
# Imagen oficial de Node con SQLite integrado (node:sqlite). Cero dependencias.
FROM node:22-alpine

ENV NODE_ENV=production

WORKDIR /app

# Solo se necesita el servidor y las paginas estaticas
COPY server.js ./
COPY public ./public

# Puerto de la aplicacion (el compose puede sobreescribirlo)
ENV PORT=3100
EXPOSE 3100

# El archivo de la base se guarda en /app/data (volumen en el compose)
ENV DB_FILE=/app/data/rastreogps.db

CMD ["node", "server.js"]