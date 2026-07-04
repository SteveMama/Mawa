import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Mawa Brain",
  description: "Control plane and connector status for the Mawa wall companion",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
