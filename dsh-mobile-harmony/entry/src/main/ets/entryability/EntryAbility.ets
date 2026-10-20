import { AbilityConstant, UIAbility, Want } from '@kit.AbilityKit';
import { window } from '@kit.ArkUI';

export default class EntryAbility extends UIAbility {
  onCreate(want: Want, launchParam: AbilityConstant.LaunchParam): void {
    AppStorage.setOrCreate('openChat', (want.parameters?.['chat'] as string) ?? '');
  }

  onNewWant(want: Want, launchParam: AbilityConstant.LaunchParam): void {
    AppStorage.setOrCreate('openChat', (want.parameters?.['chat'] as string) ?? '');
  }

  onWindowStageCreate(windowStage: window.WindowStage): void {
    windowStage.loadContent('pages/Index', (err) => {
      if (err.code) {
        console.error('dsh load page failed ' + JSON.stringify(err));
      }
    });
  }

  onForeground(): void {
    AppStorage.setOrCreate('foreground', true);
  }

  onBackground(): void {
    AppStorage.setOrCreate('foreground', false);
  }
}
