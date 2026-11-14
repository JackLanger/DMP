import { createElement } from "react";
import { createRoot } from "react-dom/client";
import { ServerView } from "@dmp/frontend/server";
import { greet } from "@dmp/frontend/rust";
import "@dmp/frontend/server.css";

createRoot(document.getElementById("app")!).render(createElement(ServerView, { greet }));
