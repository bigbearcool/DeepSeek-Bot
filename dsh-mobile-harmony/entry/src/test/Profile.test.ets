import { describe, it, expect } from '@ohos/hypium';
import { parseProfile } from '../main/ets/common/Models';
import { J } from '../main/ets/common/Json';

export default function profileTest() {
  describe('profile', () => {
    it('profilePreservesThinkingOverrideAndDefault', 0, () => {
      expect(parseProfile(J.parse('{"id":"a","model":"m","effort":"high"}')).effort).assertEqual('high');
      expect(parseProfile(J.parse('{"id":"a","effort":null}')).effort).assertNull();
    });
  });
}
