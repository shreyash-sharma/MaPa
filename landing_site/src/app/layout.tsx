import type { Metadata, Viewport } from "next";
import {
  Instrument_Serif,
  Noto_Nastaliq_Urdu,
  Noto_Serif_Bengali,
  Noto_Serif_Devanagari,
  Noto_Serif_JP,
  Noto_Serif_KR,
  Noto_Serif_Tamil,
  Noto_Serif_Telugu,
} from "next/font/google";
import "./globals.css";
import { LanguageLayer } from "@/components/LanguageLayer";
import i18n from "@/i18n/languages.json";

// Runs before React: picks the language (?lang=, a saved choice, then the browser's languages) so the page can
// hold back a bundled translation for a moment instead of flashing English. Never blocks for more than 2.5 s.
const chooseLanguage = `(function(){var d=document.documentElement;d.classList.add('js');try{
var ok=${JSON.stringify(["en", ...i18n.bundled, ...i18n.live])},bundled=${JSON.stringify(i18n.bundled)};
var p=new URLSearchParams(location.search);if(p.get('i18n'))d.setAttribute('data-i18n',p.get('i18n'));
var q=p.get('lang');if(q){q=q.toLowerCase().split('-')[0];localStorage.setItem('mapa-lang',q)}
var c=q||localStorage.getItem('mapa-lang');
if(!c){var n=navigator.languages||[navigator.language||'en'];for(var i=0;i<n.length;i++){var b=String(n[i]).toLowerCase().split('-')[0];if(ok.indexOf(b)>=0){c=b;break}}}
if(!c||ok.indexOf(c)<0||d.getAttribute('data-i18n')==='harvest')c='en';
d.setAttribute('data-lang',c);
if(bundled.indexOf(c)>=0){d.classList.add('i18n-pending');setTimeout(function(){d.classList.remove('i18n-pending')},2500)}
}catch(e){}})();`;

const instrument = Instrument_Serif({
  variable: "--font-instrument",
  subsets: ["latin"],
  weight: "400",
  style: ["normal", "italic"],
});

// Instrument Serif has no glyphs for these scripts, so the hero wordmark falls back to Noto. Not preloaded.
const notoDeva = Noto_Serif_Devanagari({ variable: "--font-noto-deva", subsets: ["devanagari"], weight: ["300", "400"], preload: false });
const notoTamil = Noto_Serif_Tamil({ variable: "--font-noto-tamil", subsets: ["tamil"], weight: ["300", "400"], preload: false });
const notoTelugu = Noto_Serif_Telugu({ variable: "--font-noto-telugu", subsets: ["telugu"], weight: ["300", "400"], preload: false });
const notoBengali = Noto_Serif_Bengali({ variable: "--font-noto-bengali", subsets: ["bengali"], weight: ["300", "400"], preload: false });
const notoUrdu = Noto_Nastaliq_Urdu({ variable: "--font-noto-urdu", subsets: ["arabic"], weight: ["400"], preload: false });
const notoJp = Noto_Serif_JP({ variable: "--font-noto-jp", weight: ["300", "400"], preload: false });
const notoKr = Noto_Serif_KR({ variable: "--font-noto-kr", weight: ["300", "400"], preload: false });

export const metadata: Metadata = {
  title: "MaPa — A calm daily picture of your parent's day",
  description:
    "MaPa learns your parent's own daily rhythm on their phone and gives the family a calm picture they can glance at. Only conclusions leave the phone.",
  icons: { icon: "/mapa-emblem-hd.png" },
  openGraph: {
    title: "MaPa — A calm daily picture of your parent's day",
    description:
      "A learned, on-device rhythm for each parent. A calm daily picture for the family. Only conclusions leave the phone.",
    type: "website",
  },
};

export const viewport: Viewport = {
  themeColor: "#fcf9f4",
};

const fontVars = [instrument, notoDeva, notoTamil, notoTelugu, notoBengali, notoUrdu, notoJp, notoKr].map((f) => f.variable).join(" ");

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="en" className={fontVars} suppressHydrationWarning>
      <head>
        <script dangerouslySetInnerHTML={{ __html: chooseLanguage }} />
      </head>
      <body className="min-h-screen overflow-x-hidden">
        {children}
        <LanguageLayer />
      </body>
    </html>
  );
}
