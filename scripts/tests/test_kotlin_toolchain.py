import importlib.util
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("kotlin_toolchain", Path(__file__).resolve().parents[1] / "verify_kotlin_toolchain.py")
toolchain = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(toolchain)


class KotlinToolchainTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.write("ARESLib-Kotlin/build.gradle.kts", '''
            kotlin("jvm") version "2.4.21" apply false
            kotlin("plugin.serialization") version "2.4.21" apply false
            id("org.jetbrains.kotlin.android") version "2.4.21" apply false
        ''' + self.stdlib())
        self.write("ARES-Analytics/build.gradle.kts", '''
            kotlin("jvm") version "2.4.21" apply false
            kotlin("plugin.serialization") version "2.4.21" apply false
            id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
        ''')
        for product in ("ARES-FTC", "ARES-FTC-Starter"):
            self.write(f"{product}/build.gradle", "classpath 'org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21'\n" + self.stdlib())
        for product in ("ARES-FRC", "ARES-FRC-Starter"):
            self.write(f"{product}/build.gradle", 'id "org.jetbrains.kotlin.jvm" version "2.4.21"\n')
        self.write("ARESLib-Kotlin/codegen/build.gradle.kts", 'testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.21")\n')
        self.write("ARES-Analytics/app/build.gradle.kts", 'testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.21")\n')

    @staticmethod
    def stdlib():
        return "\n".join(f'force("org.jetbrains.kotlin:{name}:2.4.21")' for name in
                         ("kotlin-stdlib", "kotlin-stdlib-jdk7", "kotlin-stdlib-jdk8", "kotlin-stdlib-common"))

    def write(self, name, text):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def replace(self, name, before, after):
        path = self.root / name
        path.write_text(path.read_text(encoding="utf-8").replace(before, after), encoding="utf-8")

    def assert_invalid(self, diagnostic):
        _, _, errors = toolchain.verify(self.root)
        self.assertTrue(errors)
        self.assertIn(diagnostic, "\n".join(errors))

    def test_aligned_cohort_checks_all_pins(self):
        self.assertEqual(toolchain.verify(self.root), ("2.4.21", 24, []))

    def test_each_consumer_must_follow_library_compiler(self):
        for product, name in (("ARES-Analytics", "build.gradle.kts"), ("ARES-FTC", "build.gradle"),
                              ("ARES-FTC-Starter", "build.gradle"), ("ARES-FRC", "build.gradle"),
                              ("ARES-FRC-Starter", "build.gradle")):
            path = f"{product}/{name}"
            with self.subTest(product=product):
                original = (self.root / path).read_text(encoding="utf-8")
                self.replace(path, "2.4.21", "2.4.20")
                self.assert_invalid(path)
                self.write(path, original)

    def test_serialization_and_compose_cannot_drift_independently(self):
        for plugin in ("plugin.serialization", "org.jetbrains.kotlin.plugin.compose"):
            with self.subTest(plugin=plugin):
                name = "ARES-Analytics/build.gradle.kts"
                original = (self.root / name).read_text(encoding="utf-8")
                self.replace(name, f'{plugin}") version "2.4.21"', f'{plugin}") version "2.4.20"')
                self.assert_invalid("ARES-Analytics")
                self.write(name, original)

    def test_forced_standard_library_downgrade_is_detected(self):
        for product in ("ARESLib-Kotlin", "ARES-FTC", "ARES-FTC-Starter"):
            name = product + ("/build.gradle.kts" if product == "ARESLib-Kotlin" else "/build.gradle")
            with self.subTest(product=product):
                original = (self.root / name).read_text(encoding="utf-8")
                self.replace(name, "kotlin-stdlib-jdk8:2.4.21", "kotlin-stdlib-jdk8:2.4.10")
                self.assert_invalid("kotlin-stdlib-jdk8")
                self.write(name, original)

    def test_generated_code_uses_current_embedded_compiler(self):
        self.replace("ARESLib-Kotlin/codegen/build.gradle.kts", "2.4.21", "2.4.10")
        self.assert_invalid("embedded generated-code compiler")

    def test_comments_cannot_supply_a_missing_plugin(self):
        self.replace("ARESLib-Kotlin/build.gradle.kts", 'kotlin("jvm") version', '// kotlin("jvm") version')
        self.assert_invalid("expected one active Kotlin/JVM pin, found 0")

    def test_duplicate_active_plugin_is_rejected(self):
        name = "ARES-Analytics/build.gradle.kts"
        text = (self.root / name).read_text(encoding="utf-8")
        self.write(name, text + '\nkotlin("jvm") version "2.4.21"\n')
        self.assert_invalid("found 2")

    def test_historical_comments_and_repository_urls_do_not_change_cohort(self):
        name = "ARES-Analytics/build.gradle.kts"
        text = (self.root / name).read_text(encoding="utf-8")
        self.write(name, text + '\n// kotlin("jvm") version "1.9.0"\n/* 2.4.10 */\nurl = "https://example.invalid/repository"\n')
        self.assertEqual(toolchain.verify(self.root), ("2.4.21", 24, []))

    def test_missing_product_is_actionable(self):
        (self.root / "ARES-FRC/build.gradle").unlink()
        self.assert_invalid("ARES-FRC/build.gradle: compiler configuration is missing")


if __name__ == "__main__":
    unittest.main()
