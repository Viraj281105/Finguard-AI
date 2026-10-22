import { useCallback, useEffect, useState } from "react";

interface ResourceState<T> {
  data: T | null;
  loading: boolean;
  error: string;
}

/**
 * Loads data on mount and exposes `reload` (refetch without flashing the loading state) and
 * `update` (change the loaded data locally, e.g. after adding or deleting a row).
 *
 * `fetcher` must be a stable reference (a module-level function or `useCallback`).
 */
export function useResource<T>(fetcher: () => Promise<T>, errorMessage: string) {
  const [state, setState] = useState<ResourceState<T>>({ data: null, loading: true, error: "" });
  const [version, setVersion] = useState(0);

  useEffect(() => {
    let active = true;
    fetcher()
      .then((data) => {
        if (active) setState({ data, loading: false, error: "" });
      })
      .catch(() => {
        if (active) setState((prev) => ({ ...prev, loading: false, error: errorMessage }));
      });
    return () => {
      active = false;
    };
  }, [fetcher, errorMessage, version]);

  const reload = useCallback(() => setVersion((v) => v + 1), []);

  const update = useCallback(
    (change: (current: T) => T) =>
      setState((prev) => (prev.data === null ? prev : { ...prev, data: change(prev.data) })),
    [],
  );

  return { ...state, reload, update };
}
