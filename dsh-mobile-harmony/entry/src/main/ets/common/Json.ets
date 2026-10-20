/** Small helpers to read the computer's answers safely. */
export type JObj = Record<string, Object>;

export class J {
  static parse(text: string): JObj {
    try {
      const v = JSON.parse(text.length > 0 ? text : '{}') as Object;
      if (v !== null && v !== undefined && typeof v === 'object' && !Array.isArray(v)) {
        return v as JObj;
      }
    } catch (e) {
      // not JSON
    }
    const empty: JObj = {};
    return empty;
  }

  static has(o: JObj, k: string): boolean {
    const v = o[k];
    return v !== undefined && v !== null;
  }

  static str(o: JObj, k: string): string | null {
    const v = o[k];
    if (v === undefined || v === null) {
      return null;
    }
    if (typeof v === 'string') {
      const s = v as string;
      return s.length > 0 ? s : null;
    }
    if (typeof v === 'number' || typeof v === 'boolean') {
      return String(v);
    }
    return null;
  }

  static strOr(o: JObj, k: string, d: string): string {
    const s = J.str(o, k);
    return s === null ? d : s;
  }

  static num(o: JObj, k: string): number | null {
    const v = o[k];
    if (v === undefined || v === null) {
      return null;
    }
    if (typeof v === 'number') {
      return v as number;
    }
    if (typeof v === 'string') {
      const n = Number(v as string);
      return isNaN(n) ? null : n;
    }
    return null;
  }

  static numOr(o: JObj, k: string, d: number): number {
    const n = J.num(o, k);
    return n === null ? d : n;
  }

  static bool(o: JObj, k: string, d: boolean): boolean {
    const v = o[k];
    if (typeof v === 'boolean') {
      return v as boolean;
    }
    return d;
  }

  static obj(o: JObj, k: string): JObj | null {
    const v = o[k];
    if (v !== undefined && v !== null && typeof v === 'object' && !Array.isArray(v)) {
      return v as JObj;
    }
    return null;
  }

  static arr(o: JObj, k: string): Object[] {
    const v = o[k];
    if (v !== undefined && v !== null && Array.isArray(v)) {
      return v as Object[];
    }
    return [];
  }

  static objs(o: JObj, k: string): JObj[] {
    const out: JObj[] = [];
    for (const x of J.arr(o, k)) {
      if (x !== null && x !== undefined && typeof x === 'object' && !Array.isArray(x)) {
        out.push(x as JObj);
      }
    }
    return out;
  }

  static strs(o: JObj, k: string): string[] {
    const out: string[] = [];
    for (const x of J.arr(o, k)) {
      if (typeof x === 'string' && (x as string).length > 0) {
        out.push(x as string);
      }
    }
    return out;
  }
}
