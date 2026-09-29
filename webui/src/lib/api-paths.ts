const API_PREFIX = "/api/v1";

export const NOTIFICATIONS_URL = "/notifications";
export const SUBSCRIBE_URL = "/push/subscribe";
export const UNSUBSCRIBE_URL = "/push/unsubscribe";

export function apiPath(path: string) {
  return API_PREFIX + path;
}
