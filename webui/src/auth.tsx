import { render } from "preact";
import { AuthPage } from "./components/AuthPage";
import "./auth.css";

const root = document.getElementById("auth-root");
if (!root) throw new Error("Missing auth root");
const { exchangePath, codeLength, ttlMinutes } = root.dataset;
if (!exchangePath?.startsWith("/") || !codeLength || !ttlMinutes) {
  throw new Error("Missing server auth configuration");
}

render(<AuthPage
  exchangePath={exchangePath}
  codeLength={codeLength}
  ttlMinutes={ttlMinutes}
  initialTicket={new URLSearchParams(window.location.hash.slice(1)).get("ticket")}
/>, root);
