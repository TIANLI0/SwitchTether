import { describe, expect, it } from "vitest";
import i18next from "i18next";
import { switchTetherBrand } from "@/i18n";
import zh from "@/i18n/locales/zh.json";

describe("brand post-processor", () => {
  it("rebrands upstream strings but can be opted out for the 'based on' line", async () => {
    const i18n = i18next.createInstance();
    await i18n.use(switchTetherBrand).init({
      lng: "zh",
      resources: { zh: { translation: zh } },
      postProcess: ["switchtetherBrand"],
      interpolation: { escapeValue: false },
    });
    expect(i18n.t("remote.basedOn", { version: "4.0.31" })).toBe(
      "基于 SwitchTether v4.0.31",
    );
    expect(
      i18n.t("remote.basedOn", { version: "4.0.31", postProcess: [] }),
    ).toBe("基于 CC Switch v4.0.31");
  });
});
