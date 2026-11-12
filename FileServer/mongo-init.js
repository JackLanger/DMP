const database = process.env.MONGO_DATABASE;
const username = process.env.MONGO_APP_USER;
const password = process.env.MONGO_APP_PASSWORD;
const uriSafe = /^[A-Za-z0-9._~-]+$/;

if (!database || !username || !password) {
  throw new Error("MONGO_DATABASE, MONGO_APP_USER and MONGO_APP_PASSWORD must be set");
}
if (!/^[A-Za-z0-9_-]+$/.test(database)) {
  throw new Error("MONGO_DATABASE must contain only letters, digits, underscores and hyphens");
}
if (![username, password].every(value => uriSafe.test(value))) {
  throw new Error("MongoDB application user and password must contain only URI-unreserved characters");
}

const appDb = db.getSiblingDB(database);
if (appDb.getUser(username)) {
  throw new Error(`MongoDB user ${username} already exists in ${database}; manage it separately`);
}
appDb.createUser({
  user: username,
  pwd: password,
  roles: [{ role: "readWrite", db: database }]
});
