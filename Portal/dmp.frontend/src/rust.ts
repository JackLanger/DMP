import init, { greet as rustGreet } from "../rust/pkg/dmp_frontend_core.js";

let initialization: ReturnType<typeof init> | undefined;

export async function greet(name: string): Promise<string> {
  if (!initialization) {
    initialization = init().catch((error: unknown) => {
      initialization = undefined;
      throw error;
    });
  }
  await initialization;
  return rustGreet(name);
}
