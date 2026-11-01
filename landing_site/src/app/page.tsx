import { Charter } from "@/components/Charter";
import { EarlyAccess } from "@/components/EarlyAccess";
import { Faq } from "@/components/Faq";
import { Footer } from "@/components/Footer";
import { Hero } from "@/components/Hero";
import { HowItLearns } from "@/components/HowItLearns";
import { Masthead } from "@/components/Masthead";
import { ModelLive } from "@/components/ModelLive";
import { ModelSheet } from "@/components/ModelSheet";

export default function Home() {
  return (
    <>
      <Masthead />
      <main className="relative flex w-full flex-col">
        <Hero />
        <ModelLive />
        <HowItLearns />
        <ModelSheet />
        <Charter />
        <Faq />
        <EarlyAccess />
      </main>
      <Footer />
    </>
  );
}
