import { HealthDashboard } from "@/components/health-dashboard";

export default function Home() {
  return (
    <main className="flex flex-1 items-center justify-center bg-neutral-50 p-6 dark:bg-neutral-950">
      <HealthDashboard />
    </main>
  );
}
