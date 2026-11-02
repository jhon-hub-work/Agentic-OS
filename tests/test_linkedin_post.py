"""The LinkedIn post is only the text between <post> tags: the agent's research notes never go out (Jhon, 2 Nov)."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import ig_carousel  # noqa: E402


class LinkedInPostText(unittest.TestCase):
    def test_notes_before_the_post_are_dropped(self):
        out = "Jhon, I'll check Anthropic's write-up first.Original post is up.<post>Your AI is\n\n#AI</post>"
        self.assertEqual(ig_carousel.linkedin_text(out), "Your AI is\n\n#AI")

    def test_no_post_tags_means_nothing_to_post(self):
        self.assertEqual(ig_carousel.linkedin_text("I'll verify the bank list before writing the post."), "")

    def test_last_post_wins_and_fences_are_stripped(self):
        self.assertEqual(ig_carousel.linkedin_text("<post>draft</post> notes <post>```\nfinal\n```</post>"), "final")

    def test_the_format_asks_for_the_tags(self):
        self.assertIn("<post>", ig_carousel.LINKEDIN_FORMAT)


if __name__ == "__main__":
    unittest.main()
