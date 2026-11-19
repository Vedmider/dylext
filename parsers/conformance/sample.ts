import { readFile } from 'fs';
import * as os from 'os';

export const MAX_LEN: number = 10;

export type UserId = string;

export interface Repo {
  find(id: UserId): string;
}

export enum Status { Active, Closed }

/** Greets people. */
export class Greeter {
  prefix: string;
  constructor(prefix: string) { this.prefix = prefix; }
  hello(name: string): string { return this.prefix + name.toUpperCase(); }
}

export function plain(a: number, b: number): number { return add(a, b); }

export const shout = (name: string): string => { return name.toUpperCase(); };
